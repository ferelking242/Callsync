# CallSync

CallSync surveille le dossier d'enregistrements du téléphone source et le rend
disponible au téléphone pair.

## Modes de transfert

- **P2P par défaut** : le téléphone source expose directement son dossier sur
  le port TCP CallSync. Le QR code contient l'identité, les adresses réseau,
  le port et une clé d'accès persistante.
- **Reprise automatique** : le client compare le manifeste, ne télécharge que
  les fichiers absents ou modifiés, reprend les fichiers `.part` après une
  coupure et vérifie leur SHA-256 avant le renommage final.
- **Pairage durable** : l'identité du pair est conservée après redémarrage,
  changement de réseau et mise à jour. Il n'y a pas d'expiration automatique.
- **Serveur legacy facultatif** : l'ancien upload HTTP reste disponible dans
  les réglages, mais il est désactivé par défaut et ne devient actif que si
  l'option correspondante est sélectionnée.

Le projet Android principal est à la racine. Le client Flutter historique est
conservé dans `legacy-client/` pour rester compatible avec les appareils qui
l'utilisent encore.

## Limite réseau importante

Le P2P direct fonctionne lorsque l'adresse annoncée est joignable depuis le
pair. Les réseaux mobiles derrière un CGNAT peuvent empêcher une connexion
TCP directe sans relais ou redirection de port. Aucun client Android ne peut
contourner un CGNAT uniquement avec un QR code.