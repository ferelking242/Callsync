# CallSync Client

Client Flutter pair-à-pair pour recevoir les enregistrements CallSync.

## Fonctionnalités

- Reconnexion automatique au pair mémorisé.
- Manifeste distant et comparaison SHA-256.
- Téléchargement uniquement des nouveautés ou fichiers modifiés.
- Reprise d’un fichier interrompu via un fichier temporaire.
- Lecture audio locale après synchronisation.
- Aucun fichier stocké sur un serveur central.

## Flux pair-à-pair

```text
[Téléphone source]
      |  CallSync surveille et expose le dossier
      |  manifeste + socket authentifiée
      v
[CallSync Client]
      |  compare les SHA-256 locaux
      -> télécharge les nouveautés/modifications
      -> reprend les fichiers partiels
      -> stocke localement
```

## Configuration

1. Ouvrez CallSync sur le téléphone source.
2. Copiez le code affiché dans la carte **Partage pair-à-pair**.
3. Ouvrez le client et allez dans **Paramètres**.
4. Collez le code et appuyez sur **Lier & synchroniser**.
5. Le client réessaie périodiquement lorsque le réseau est disponible.

Le protocole direct fonctionne lorsque l’adresse annoncée par le code est joignable
(réseau local, IP publique routable ou port redirigé). Deux téléphones derrière
des CGNAT différents ont besoin d’un rendez-vous ICE/WebRTC ou d’un relais
optionnel : ce composant ne doit pas stocker les fichiers.