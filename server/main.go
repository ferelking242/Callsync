package main

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"golang.org/x/crypto/bcrypt"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"github.com/golang-jwt/jwt/v5"
)

var jwtKey = []byte("callsync_secret_security_key_2026")

// ── Models ─────────────────────────────────────────────────────────────────────

type User struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	Username  string    `gorm:"uniqueIndex;not null" json:"username"`
	Password  string    `gorm:"not null" json:"-"`
	CreatedAt time.Time `json:"created_at"`
}

type Device struct {
	ID             string    `gorm:"primaryKey" json:"id"`
	Name           string    `gorm:"not null" json:"name"`
	AndroidVersion string    `json:"android_version"`
	LastSeen       time.Time `json:"last_seen"`
}

type Recording struct {
	ID           uint      `gorm:"primaryKey" json:"id"`
	Name         string    `gorm:"not null" json:"name"`
	Size         int64     `gorm:"not null" json:"size"`
	SHA256       string    `gorm:"uniqueIndex;not null" json:"sha256"`
	Duration     float64   `json:"duration"`
	UploadDate   time.Time `json:"upload_date"`
	CreationDate time.Time `json:"creation_date"`
	Path         string    `gorm:"not null" json:"path"`
	DeviceID     string    `gorm:"not null" json:"device_id"`
	Device       Device    `gorm:"foreignKey:DeviceID" json:"device"`
}

// DeleteCommand — order queued by Flutter client, consumed by Android uploader
type DeleteCommand struct {
	ID          uint      `gorm:"primaryKey" json:"id"`
	DeviceID    string    `gorm:"not null;index" json:"device_id"`
	SHA256      string    `gorm:"not null" json:"sha256"`
	RecordingID uint      `json:"recording_id"`
	CreatedAt   time.Time `json:"created_at"`
}

var db *gorm.DB

func initDB() {
	var err error
	db, err = gorm.Open(sqlite.Open("callsync.db"), &gorm.Config{})
	if err != nil {
		log.Fatalf("Failed to connect to database: %v", err)
	}
	if err = db.AutoMigrate(&User{}, &Device{}, &Recording{}, &DeleteCommand{}); err != nil {
		log.Fatalf("Migration failed: %v", err)
	}

	var userCount int64
	db.Model(&User{}).Count(&userCount)
	if userCount == 0 {
		hash, _ := bcrypt.GenerateFromPassword([]byte("admin123"), bcrypt.DefaultCost)
		db.Create(&User{Username: "admin", Password: string(hash), CreatedAt: time.Now()})
		log.Println("Seeded default admin (admin/admin123)")
	}
}

// ── JWT ────────────────────────────────────────────────────────────────────────

type Claims struct {
	Username string `json:"username"`
	jwt.RegisteredClaims
}

func generateToken(username string) (string, error) {
	claims := &Claims{
		Username: username,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(24 * time.Hour)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
		},
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(jwtKey)
}

func authMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		header := c.GetHeader("Authorization")
		if header == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Authorization header missing"})
			return
		}
		parts := strings.Split(header, " ")
		if len(parts) != 2 || parts[0] != "Bearer" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Invalid Authorization format"})
			return
		}
		claims := &Claims{}
		token, err := jwt.ParseWithClaims(parts[1], claims, func(t *jwt.Token) (interface{}, error) {
			return jwtKey, nil
		})
		if err != nil || !token.Valid {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Invalid or expired token"})
			return
		}
		c.Set("username", claims.Username)
		c.Next()
	}
}

// ── main ───────────────────────────────────────────────────────────────────────

func main() {
	initDB()

	r := gin.Default()
	r.Use(func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Authorization,Content-Type")
		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}
		c.Next()
	})

	// Public
	r.GET("/health", handleHealth)
	r.POST("/login", handleLogin)

	// Protected
	auth := r.Group("/")
	auth.Use(authMiddleware())
	{
		auth.POST("/upload", handleUpload)
		auth.GET("/records", handleGetRecords)
		auth.GET("/record/:id", handleGetRecordDetails)
		auth.GET("/stream/:id", handleStreamRecord)
		auth.DELETE("/record/:id", handleDeleteRecord)
		auth.DELETE("/purge-all", handlePurgeAll)

		// Delete-at-source commands
		auth.POST("/delete-commands", handleCreateDeleteCommands)
		auth.GET("/pending-commands/:device_id", handleGetPendingCommands)
		auth.DELETE("/delete-command/:id", handleAcknowledgeDeleteCommand)
	}

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	log.Printf("CallSync server starting on :%s", port)
	if err := r.Run(":" + port); err != nil {
		log.Fatalf("Server failed: %v", err)
	}
}

// ── Handlers ───────────────────────────────────────────────────────────────────

func handleHealth(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":  "healthy",
		"time":    time.Now().Format(time.RFC3339),
		"version": "2.0.0",
	})
}

type LoginInput struct {
	Username string `json:"username" binding:"required"`
	Password string `json:"password" binding:"required"`
}

func handleLogin(c *gin.Context) {
	var input LoginInput
	if err := c.ShouldBindJSON(&input); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid payload"})
		return
	}
	var user User
	if err := db.Where("username = ?", input.Username).First(&user).Error; err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid credentials"})
		return
	}
	if err := bcrypt.CompareHashAndPassword([]byte(user.Password), []byte(input.Password)); err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid credentials"})
		return
	}
	token, err := generateToken(user.Username)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Token generation failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"token": token})
}

func handleUpload(c *gin.Context) {
	phoneID       := c.PostForm("phone_id")
	deviceName    := c.PostForm("device_name")
	androidVersion:= c.PostForm("android_version")
	timestampStr  := c.PostForm("timestamp")
	clientSHA256  := c.PostForm("sha256")

	if phoneID == "" || deviceName == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Missing phone_id or device_name"})
		return
	}

	fileHeader, err := c.FormFile("file")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "No file uploaded"})
		return
	}

	file, err := fileHeader.Open()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to open file"})
		return
	}
	defer file.Close()

	hasher := sha256.New()
	if _, err := io.Copy(hasher, file); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "SHA256 calculation failed"})
		return
	}
	computedSHA256 := hex.EncodeToString(hasher.Sum(nil))

	if clientSHA256 != "" && clientSHA256 != computedSHA256 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "SHA256 mismatch"})
		return
	}

	// Register/update device
	db.Save(&Device{ID: phoneID, Name: deviceName, AndroidVersion: androidVersion, LastSeen: time.Now()})

	// Dedup check
	var existing Recording
	if err := db.Where("sha256 = ?", computedSHA256).First(&existing).Error; err == nil {
		log.Printf("[Upload] Duplicate: %s", computedSHA256)
		c.JSON(http.StatusConflict, gin.H{"message": "Already uploaded", "id": existing.ID})
		return
	}

	// Save file
	safeFilename := filepath.Base(fileHeader.Filename)
	safeFilename  = strings.Map(func(r rune) rune {
		if r == '/' || r == '\\' || r == '\000' { return '_' }
		return r
	}, safeFilename)

	uploadDir := "uploads"
	os.MkdirAll(uploadDir, 0755)
	targetPath := filepath.Join(uploadDir, fmt.Sprintf("%d_%s", time.Now().UnixNano(), safeFilename))

	// Rewind and copy
	file.Seek(0, io.SeekStart)
	out, err := os.Create(targetPath)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to save file"})
		return
	}
	if _, err = io.Copy(out, file); err != nil {
		out.Close(); os.Remove(targetPath)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to write file"})
		return
	}
	out.Close()

	// Parse creation time from timestamp
	var creationTime time.Time
	if ts, err := strconv.ParseInt(timestampStr, 10, 64); err == nil {
		if ts > 1e12 {
			creationTime = time.UnixMilli(ts)
		} else {
			creationTime = time.Unix(ts, 0)
		}
	} else {
		creationTime = time.Now()
	}

	recording := Recording{
		Name:         safeFilename,
		Size:         fileHeader.Size,
		SHA256:       computedSHA256,
		UploadDate:   time.Now(),
		CreationDate: creationTime,
		Path:         targetPath,
		DeviceID:     phoneID,
	}
	if err := db.Create(&recording).Error; err != nil {
		os.Remove(targetPath)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "DB write failed"})
		return
	}

	log.Printf("[Upload] %s from %s (%d bytes)", safeFilename, deviceName, fileHeader.Size)
	c.JSON(http.StatusOK, gin.H{"message": "Upload successful", "id": recording.ID})
}

func handleGetRecords(c *gin.Context) {
	var recordings []Recording
	if err := db.Preload("Device").Order("creation_date DESC").Find(&recordings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "DB error"})
		return
	}
	c.JSON(http.StatusOK, recordings)
}

func handleGetRecordDetails(c *gin.Context) {
	id, err := strconv.ParseUint(c.Param("id"), 10, 32)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid ID"})
		return
	}
	var rec Recording
	if err := db.Preload("Device").First(&rec, id).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "Not found"})
		} else {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "DB error"})
		}
		return
	}
	c.JSON(http.StatusOK, rec)
}

func handleStreamRecord(c *gin.Context) {
	id, err := strconv.ParseUint(c.Param("id"), 10, 32)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid ID"})
		return
	}
	var rec Recording
	if err := db.First(&rec, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Not found"})
		return
	}
	if _, err := os.Stat(rec.Path); os.IsNotExist(err) {
		c.JSON(http.StatusNotFound, gin.H{"error": "File missing on disk"})
		return
	}
	c.Header("Content-Type", "audio/mpeg")
	c.Header("Accept-Ranges", "bytes")
	c.File(rec.Path)
}

func handleDeleteRecord(c *gin.Context) {
	id, err := strconv.ParseUint(c.Param("id"), 10, 32)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid ID"})
		return
	}
	var rec Recording
	if err := db.First(&rec, id).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "Not found"})
		} else {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "DB error"})
		}
		return
	}
	if err := db.Delete(&rec).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Delete failed"})
		return
	}
	if err := os.Remove(rec.Path); err != nil {
		log.Printf("[Delete] Warning: could not remove file %s: %v", rec.Path, err)
	}
	log.Printf("[Delete] Recording %d (%s) deleted", rec.ID, rec.Name)
	c.JSON(http.StatusOK, gin.H{"message": "Deleted"})
}

// DELETE /purge-all — remove ALL recordings from server (server + disk)
func handlePurgeAll(c *gin.Context) {
	var recordings []Recording
	db.Find(&recordings)

	deleted, errs := 0, 0
	for _, rec := range recordings {
		if err := db.Delete(&rec).Error; err != nil {
			errs++
			continue
		}
		if err := os.Remove(rec.Path); err != nil {
			log.Printf("[Purge] Warning: could not remove %s: %v", rec.Path, err)
		}
		deleted++
	}
	log.Printf("[Purge] Deleted %d recordings (%d errors)", deleted, errs)
	c.JSON(http.StatusOK, gin.H{
		"message": "Purge complete",
		"deleted": deleted,
		"errors":  errs,
		"total":   len(recordings),
	})
}

// POST /delete-commands — Flutter client queues deletion orders for a device
// Body: { "device_id": "...", "sha256_list": ["...", "..."] }
func handleCreateDeleteCommands(c *gin.Context) {
	var body struct {
		DeviceID   string   `json:"device_id" binding:"required"`
		SHA256List []string `json:"sha256_list" binding:"required"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid payload"})
		return
	}
	if len(body.SHA256List) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "sha256_list is empty"})
		return
	}

	created := 0
	for _, sha := range body.SHA256List {
		// Find the recording to get its ID (only queue if it's actually on server)
		var rec Recording
		recID := uint(0)
		if err := db.Where("sha256 = ?", sha).First(&rec).Error; err == nil {
			recID = rec.ID
		}

		cmd := DeleteCommand{
			DeviceID:    body.DeviceID,
			SHA256:      sha,
			RecordingID: recID,
			CreatedAt:   time.Now(),
		}
		if err := db.Create(&cmd).Error; err == nil {
			created++
		}
	}

	log.Printf("[DeleteCmd] %d command(s) queued for device %s", created, body.DeviceID)
	c.JSON(http.StatusOK, gin.H{"message": "Commands queued", "created": created})
}

// GET /pending-commands/:device_id — Android uploader polls this
func handleGetPendingCommands(c *gin.Context) {
	deviceID := c.Param("device_id")
	if deviceID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Missing device_id"})
		return
	}
	var commands []DeleteCommand
	if err := db.Where("device_id = ?", deviceID).Order("created_at ASC").Find(&commands).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "DB error"})
		return
	}
	c.JSON(http.StatusOK, commands)
}

// DELETE /delete-command/:id — Android acknowledges execution of a command
func handleAcknowledgeDeleteCommand(c *gin.Context) {
	id, err := strconv.ParseUint(c.Param("id"), 10, 32)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid ID"})
		return
	}
	if err := db.Delete(&DeleteCommand{}, id).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Delete failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "Command acknowledged"})
}
