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

// JWT key used for signing
var jwtKey = []byte("callsync_secret_security_key_2026")

// GORM Database Schemas
type User struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	Username  string    `gorm:"uniqueIndex;not null" json:"username"`
	Password  string    `gorm:"not null" json:"-"`
	CreatedAt time.Time `json:"created_at"`
}

type Device struct {
	ID             string    `gorm:"primaryKey" json:"id"` // Unique phone_id from client
	Name           string    `gorm:"not null" json:"name"`
	AndroidVersion string    `json:"android_version"`
	LastSeen       time.Time `json:"last_seen"`
}

type Recording struct {
	ID           uint      `gorm:"primaryKey" json:"id"`
	Name         string    `gorm:"not null" json:"name"`
	Size         int64     `gorm:"not null" json:"size"`
	SHA256       string    `gorm:"uniqueIndex;not null" json:"sha256"`
	Duration     float64   `json:"duration"` // in seconds
	UploadDate   time.Time `json:"upload_date"`
	CreationDate time.Time `json:"creation_date"`
	Path         string    `gorm:"not null" json:"path"` // Local absolute/relative storage path
	DeviceID     string    `gorm:"not null" json:"device_id"`
	Device       Device    `gorm:"foreignKey:DeviceID" json:"device"`
}

var db *gorm.DB

// Setup SQLite database and GORM
func initDB() {
	var err error
	dbPath := "callsync.db"
	db, err = gorm.Open(sqlite.Open(dbPath), &gorm.Config{})
	if err != nil {
		log.Fatalf("Failed to connect to SQLite database: %v", err)
	}

	// Run Auto Migrations
	err = db.AutoMigrate(&User{}, &Device{}, &Recording{})
	if err != nil {
		log.Fatalf("Database auto-migration failed: %v", err)
	}

	// Seed default administrator if table is empty
	var userCount int64
	db.Model(&User{}).Count(&userCount)
	if userCount == 0 {
		hashedPassword, err := bcrypt.GenerateFromPassword([]byte("admin123"), bcrypt.DefaultCost)
		if err != nil {
			log.Fatalf("Failed to hash default password: %v", err)
		}
		admin := User{
			Username:  "admin",
			Password:  string(hashedPassword),
			CreatedAt: time.Now(),
		}
		if err := db.Create(&admin).Error; err != nil {
			log.Printf("Warning: failed to seed default admin: %v", err)
		} else {
			log.Println("Seeded default admin user: username=admin, password=admin123")
		}
	}
}

// JWT Claims struct
type Claims struct {
	Username string `json:"username"`
	jwt.RegisteredClaims
}

// Generate JWT token for user
func generateToken(username string) (string, error) {
	expirationTime := time.Now().Add(24 * time.Hour)
	claims := &Claims{
		Username: username,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(expirationTime),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
		},
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString(jwtKey)
}

// JWT Auth Middleware
func authMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Authorization header missing"})
			c.Abort()
			return
		}

		parts := strings.Split(authHeader, " ")
		if len(parts) != 2 || parts[0] != "Bearer" {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid Authorization header format"})
			c.Abort()
			return
		}

		tokenString := parts[1]
		claims := &Claims{}

		token, err := jwt.ParseWithClaims(tokenString, claims, func(token *jwt.Token) (interface{}, error) {
			return jwtKey, nil
		})

		if err != nil || !token.Valid {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid or expired JWT token"})
			c.Abort()
			return
		}

		c.Set("username", claims.Username)
		c.Next()
	}
}

func main() {
	// Initialize Database
	initDB()

	// Ensure storage directory exists
	if err := os.MkdirAll("storage", 0755); err != nil {
		log.Fatalf("Failed to create storage directory: %v", err)
	}

	// Create Gin engine
	r := gin.Default()

	// Enable CORS
	r.Use(func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Credentials", "true")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Content-Length, Accept-Encoding, X-CSRF-Token, Authorization, accept, origin, Cache-Control, X-Requested-With")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "POST, OPTIONS, GET, PUT, DELETE")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}
		c.Next()
	})

	// Public Routes
	r.GET("/health", handleHealth)
	r.POST("/login", handleLogin)

	// Protected Routes (JWT required)
	authorized := r.Group("/")
	authorized.Use(authMiddleware())
	{
		authorized.POST("/upload", handleUpload)
		authorized.GET("/records", handleGetRecords)
		authorized.GET("/record/:id", handleGetRecordDetails)
		authorized.GET("/stream/:id", handleStreamRecord)
		authorized.DELETE("/record/:id", handleDeleteRecord)
	}

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	log.Printf("CallSync Go Server starting on port %s...", port)
	if err := r.Run(":" + port); err != nil {
		log.Fatalf("Server failed to start: %v", err)
	}
}

// Handlers

// GET /health
func handleHealth(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":  "healthy",
		"time":    time.Now().Format(time.RFC3339),
		"version": "1.0.0",
	})
}

// POST /login
type LoginInput struct {
	Username string `json:"username" binding:"required"`
	Password string `json:"password" binding:"required"`
}

func handleLogin(c *gin.Context) {
	var input LoginInput
	if err := c.ShouldBindJSON(&input); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid payload parameters"})
		return
	}

	var user User
	if err := db.Where("username = ?", input.Username).First(&user).Error; err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid username or password"})
		return
	}

	if err := bcrypt.CompareHashAndPassword([]byte(user.Password), []byte(input.Password)); err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid username or password"})
		return
	}

	token, err := generateToken(user.Username)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to generate security token"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"token": token,
	})
}

// POST /upload (Multipart data: file, phone_id, device_name, android_version, timestamp, sha256)
func handleUpload(c *gin.Context) {
	phoneID := c.PostForm("phone_id")
	deviceName := c.PostForm("device_name")
	androidVersion := c.PostForm("android_version")
	timestampStr := c.PostForm("timestamp")
	clientSHA256 := c.PostForm("sha256")

	if phoneID == "" || deviceName == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Missing phone_id or device_name"})
		return
	}

	// 1. Get the multipart file
	fileHeader, err := c.FormFile("file")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "No file uploaded"})
		return
	}

	// Open the file to parse bytes and check uniqueness
	file, err := fileHeader.Open()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to read uploaded file header"})
		return
	}
	defer file.Close()

	// Compute SHA256 of the incoming data stream
	hasher := sha256.New()
	if _, err := io.Copy(hasher, file); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to calculate SHA256 of file stream"})
		return
	}
	computedSHA256 := hex.EncodeToString(hasher.Sum(nil))

	// Validate with client-provided SHA256 if present
	if clientSHA256 != "" && clientSHA256 != computedSHA256 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "SHA256 validation mismatch. File transfer may be corrupt."})
		return
	}

	// 2. Register or update device metadata in GORM
	device := Device{
		ID:             phoneID,
		Name:           deviceName,
		AndroidVersion: androidVersion,
		LastSeen:       time.Now(),
	}
	if err := db.Save(&device).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to save device metadata"})
		return
	}

	// 3. Check if recording is already present by SHA-256 to prevent duplicate storage writes!
	var existingRecording Recording
	if err := db.Where("sha256 = ?", computedSHA256).First(&existingRecording).Error; err == nil {
		// Found duplicate! Immediately return success (200) without saving file.
		log.Printf("[Upload] Duplicate detected. Hash %s already uploaded.", computedSHA256)
		c.JSON(http.StatusOK, gin.H{
			"message": "File already exists. Skipped.",
			"id":      existingRecording.ID,
		})
		return
	}

	// 4. Secure File Saving - avoid directory traversal
	safeFilename := filepath.Base(fileHeader.Filename)
	deviceFolder := filepath.Join("storage", filepath.Clean(phoneID))
	
	// Double check that we are writing inside the storage folder to protect system paths
	cleanDeviceFolder := filepath.Clean(deviceFolder)
	if !strings.HasPrefix(cleanDeviceFolder, "storage") {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Malicious path attempt detected"})
		return
	}

	if err := os.MkdirAll(cleanDeviceFolder, 0755); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to create device storage folder"})
		return
	}

	targetPath := filepath.Join(cleanDeviceFolder, safeFilename)

	// Save the file on disk
	if err := c.SaveUploadedFile(fileHeader, targetPath); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to save file onto disk storage"})
		return
	}

	// Determine Creation Date
	creationTime := time.Now()
	if timestampStr != "" {
		if ms, err := strconv.ParseInt(timestampStr, 10, 64); err == nil {
			creationTime = time.Unix(ms/1000, (ms%1000)*1000000)
		}
	}

	// 5. Save Recording in SQLite
	recording := Recording{
		Name:         safeFilename,
		Size:         fileHeader.Size,
		SHA256:       computedSHA256,
		Duration:     0.0, // Can be extended by reading ID3 tags if needed
		UploadDate:   time.Now(),
		CreationDate: creationTime,
		Path:         targetPath,
		DeviceID:     phoneID,
	}

	if err := db.Create(&recording).Error; err != nil {
		// Clean up saved file from disk on DB write failure to avoid orphaned files
		os.Remove(targetPath)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to record entry in SQLite"})
		return
	}

	log.Printf("[Upload] New call recording uploaded from %s: %s (Size: %d bytes)", deviceName, safeFilename, fileHeader.Size)
	c.JSON(http.StatusOK, gin.H{
		"message": "Upload successful",
		"id":      recording.ID,
	})
}

// GET /records (Retrieves list of all recordings)
func handleGetRecords(c *gin.Context) {
	var recordings []Recording
	if err := db.Preload("Device").Order("creation_date DESC").Find(&recordings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to retrieve records"})
		return
	}
	c.JSON(http.StatusOK, recordings)
}

// GET /record/:id (Get single recording details)
func handleGetRecordDetails(c *gin.Context) {
	idStr := c.Param("id")
	id, err := strconv.ParseUint(idStr, 10, 32)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid recording ID format"})
		return
	}

	var recording Recording
	if err := db.Preload("Device").First(&recording, id).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "Recording not found"})
		} else {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "Database retrieval failure"})
		}
		return
	}

	c.JSON(http.StatusOK, recording)
}

// GET /stream/:id (HTTP Range Requests compatible streaming endpoint)
func handleStreamRecord(c *gin.Context) {
	idStr := c.Param("id")
	id, err := strconv.ParseUint(idStr, 10, 32)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid recording ID format"})
		return
	}

	var recording Recording
	if err := db.First(&recording, id).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "Recording not found"})
		} else {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "Database error"})
		}
		return
	}

	// Verify file exists on disk
	if _, err := os.Stat(recording.Path); os.IsNotExist(err) {
		c.JSON(http.StatusNotFound, gin.H{"error": "Recording audio file missing on storage disk"})
		return
	}

	// Serve the file. Gin's c.File automatically manages content range requests!
	// This enables seeking, play/pause and immediate playback streaming on ExoPlayer.
	c.Header("Content-Type", "audio/mpeg")
	c.Header("Accept-Ranges", "bytes")
	c.File(recording.Path)
}

// DELETE /record/:id
func handleDeleteRecord(c *gin.Context) {
	idStr := c.Param("id")
	id, err := strconv.ParseUint(idStr, 10, 32)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid recording ID format"})
		return
	}

	var recording Recording
	if err := db.First(&recording, id).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "Recording not found"})
		} else {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "Database error"})
		}
		return
	}

	// Delete from DB
	if err := db.Delete(&recording).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to delete record from SQLite"})
		return
	}

	// Delete file from storage disk
	if err := os.Remove(recording.Path); err != nil {
		// Log warning but don't fail, since we cleared DB record
		log.Printf("Warning: failed to delete file %s from disk: %v", recording.Path, err)
	}

	log.Printf("[Delete] Recording deleted: ID %d, Name: %s", recording.ID, recording.Name)
	c.JSON(http.StatusOK, gin.H{"message": "Recording deleted successfully"})
}
