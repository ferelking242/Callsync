# CallSync - Backend Go & SQLite Server

Bienvenue sur le serveur Go de **CallSync**, un système professionnel de synchronisation et de streaming en direct pour vos enregistrements d'appels Android. 

Le serveur stocke les fichiers audio originaux sans aucune modification ni compression, refuse les doublons via une validation d'empreinte SHA256, et expose une API REST sécurisée par JWT, compatible avec les **HTTP Range Requests** pour une lecture en continu instantanée (streaming) depuis l'application Android Viewer.

---

## 🚀 Fonctionnalités du Serveur

- **Authentification Sécurisée** : Endpoints protégés par des jetons JWT (JSON Web Tokens).
- **Zéro Doublon** : Calcul automatique de l'empreinte `SHA256` de chaque fichier. Si le hash existe déjà, le serveur confirme le succès sans réécrire le fichier sur le disque.
- **Stockage Propre** : Fichiers conservés sous `storage/{device_id}/{filename}` de manière non-destructive.
- **Streaming Instantané** : Support complet des requêtes partielles (`HTTP Range Requests` - Status `206 Partial Content`), permettant de naviguer (seek) instantanément dans le lecteur ExoPlayer de l'application Android.
- **Base de Données Légère** : Base de données SQLite gérée via l'ORM performant GORM.

---

## 🛠️ Prérequis

- **Go (Golang)** v1.18 ou version supérieure installé. [Télécharger Go](https://go.dev/dl/)
- Un compilateur C (GCC) si vous souhaitez compiler SQLite sous Linux/macOS, ou utilisez simplement le driver par défaut de Go GORM.

---

## 📂 Structure des API (Routes)

Le serveur Go expose les routes suivantes :

| Méthode | Route | Description | Protection |
| :--- | :--- | :--- | :--- |
| **GET** | `/health` | Vérifie l'état de santé du serveur | Publique |
| **POST** | `/login` | Authentification utilisateur & récupération JWT | Publique |
| **POST** | `/upload` | Téléverser un enregistrement d'appel (multipart) | **JWT (Bearer)** |
| **GET** | `/records` | Obtenir la liste de tous les enregistrements | **JWT (Bearer)** |
| **GET** | `/record/:id` | Obtenir les détails d'un enregistrement spécifique | **JWT (Bearer)** |
| **GET** | `/stream/:id` | Flux audio en direct (Streaming Range Requests) | **JWT (Bearer)** |
| **DELETE**| `/record/:id` | Supprimer un enregistrement de la base et du disque| **JWT (Bearer)** |

---

## 🏃 Comment Lancer le Serveur Go

### 1. Cloner le dossier ou naviguer dans le répertoire du serveur
```bash
cd server
```

### 2. Initialiser les modules Go et télécharger les dépendances
```bash
go mod init callsync_server
go get github.com/gin-gonic/gin
go get gorm.io/driver/sqlite
go get gorm.io/gorm
go get github.com/golang-jwt/jwt/v5
go get golang.org/x/crypto/bcrypt
```

### 3. Exécuter le serveur
Le serveur s'initialise automatiquement, crée le fichier de base de données `callsync.db`, applique les migrations des tables `users`, `devices` et `recordings`, puis configure un compte administrateur par défaut :
- **Nom d'utilisateur** : `admin`
- **Mot de passe** : `admin123`

```bash
go run main.go
```
*Le serveur démarrera par défaut sur le port `8080` (accessible localement via `http://localhost:8080`).*

---

## 📱 Configuration de l'Application Android (CallSync)

L'application Android unifie à la fois le module **Uploader** et le module **Viewer** au sein d'une seule interface soignée en Material 3 Dark Mode :

### Accès Rapide & Test Local (Émulateur)
1. Si vous exécutez le serveur Go localement sur votre ordinateur de développement et lancez l'application Android sur l'émulateur Android standard, configurez l'adresse IP du serveur sur : **`http://10.0.2.2:8080/`** (l'adresse spéciale pour accéder à l'hôte local depuis l'émulateur).
2. Connectez-vous avec l'identifiant par défaut : `admin` / `admin123`.

### Surveillance Automatique (FileObserver)
L'application crée un **Service Foreground Permanent** (avec notification) qui écoute en direct les événements du système (`CREATE`, `MOVED_TO`, `CLOSE_WRITE`) sur le dossier configuré (par défaut : dossier `Recordings` dans l'espace de stockage externe de l'application).

### Le Mode Bac-à-sable (Sandbox)
Pour faciliter vos tests sans avoir à passer un appel réel sur l'émulateur :
1. Dans l'onglet **Uploader**, appuyez sur le bouton **"Générer fichier .mp3"**.
2. Cela créera instantanément un fichier audio factice dans le dossier surveillé.
3. Le Foreground Service détectera le fichier, attendra sa stabilisation d'écriture, calculera son empreinte SHA256, puis le téléversera automatiquement vers le serveur Go !
4. Ouvrez l'onglet **Viewer** pour actualiser et écouter ce fichier en streaming direct via ExoPlayer !
