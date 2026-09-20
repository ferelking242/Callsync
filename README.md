# CallSync

CallSync surveille automatiquement le dossier d'enregistrements du téléphone et
envoie les nouveaux fichiers audio vers le serveur configuré.

## Fonctionnement automatique

- **Démarrage en arrière-plan** : le service démarre après le boot, le
  déverrouillage et la mise à jour de l'application sans ouvrir l'interface.
- **Détection continue** : les créations, déplacements et fins d'écriture sont
  détectés. Les dossiers SAF sont rescannés périodiquement car Android ne
  fournit pas de FileObserver fiable pour ces URI.
- **Nouveaux fichiers uniquement** : Room et SHA-256 empêchent les doublons.
  Une modification d'un fichier existant est détectée et envoyée comme une
  nouvelle version.
- **Reprise automatique** : les uploads échoués utilisent un backoff
  progressif, les erreurs permanentes ne sont pas répétées indéfiniment et la
  synchronisation reprend au retour du réseau.
- **Notification discrète** : Android impose une notification pour un service
  actif, mais le canal CallSync est silencieux, sans vibration ni badge.
- **Transfert serveur uniquement** : le partage direct entre téléphones et le
  pairage ont été retirés pour garder un seul flux fiable.

Le projet Android principal est à la racine. Le client Flutter est maintenu dans
son dépôt séparé : `ferelking242/call-sync-client`.

## Réseau

Le direct est tenté en premier lorsqu'une adresse annoncée est joignable.
Sinon, le client utilise automatiquement le relais Internet inclus dans le
serveur CallSync. Les deux appareils peuvent donc être sur des réseaux
différents et n'ont pas besoin d'être proches. Le relais ne stocke pas les
enregistrements : il transmet uniquement des commandes et des blocs en
mémoire.