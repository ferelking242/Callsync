# CallSync

CallSync surveille le dossier d'enregistrements du téléphone source et le rend
disponible au téléphone pair.

## Modes de transfert

- **P2P par défaut** : le téléphone source expose directement son dossier sur
  le port TCP CallSync. Le code de liaison contient l'identité, les adresses
  réseau, le port, le relais Internet et une clé d'accès persistante.
- **Relais Internet sans stockage** : si les deux téléphones ne peuvent pas
  ouvrir une connexion TCP directe (réseaux mobiles, box, CGNAT), les commandes
  et les blocs audio passent par le relais sécurisé en mémoire. Le relais ne
  crée ni fichier ni copie persistante.
- **Reprise automatique** : le client compare le manifeste, ne télécharge que
  les fichiers absents ou modifiés, reprend les fichiers `.part` après une
  coupure et vérifie leur SHA-256 avant le renommage final.
- **Pairage durable** : l'identité du pair est conservée après redémarrage,
  changement de réseau et mise à jour. Il n'y a pas d'expiration automatique.
- **Serveur legacy facultatif** : l'ancien upload HTTP reste disponible dans
  les réglages, mais il est désactivé par défaut et ne devient actif que si
  l'option correspondante est sélectionnée.

Le projet Android principal est à la racine. Le client Flutter est maintenu dans
son dépôt séparé : `ferelking242/call-sync-client`.

## Réseau

Le direct est tenté en premier lorsqu'une adresse annoncée est joignable.
Sinon, le client utilise automatiquement le relais Internet inclus dans le
serveur CallSync. Les deux appareils peuvent donc être sur des réseaux
différents et n'ont pas besoin d'être proches. Le relais ne stocke pas les
enregistrements : il transmet uniquement des commandes et des blocs en
mémoire.