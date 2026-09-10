import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import '../api/p2p_api.dart';
import '../models/peer.dart';
import '../models/recording.dart';
import 'storage_service.dart';

enum SyncStatus { idle, connecting, syncing, downloading, done, error }

class SyncService extends ChangeNotifier {
  SyncStatus _status = SyncStatus.idle;
  String _statusMessage = '';
  List<Recording> _peerRecords = [];
  List<DownloadedRecord> _localRecords = [];
  double _downloadProgress = 0;
  int _downloadDone = 0;
  int _downloadTotal = 0;
  String? _lastError;
  final Set<int> _downloadingIds = {};
  P2pApi? _api;
  PeerProfile? _peer;
  Timer? _keepAliveTimer;
  bool _reconnectInFlight = false;

  SyncStatus get status => _status;
  String get statusMessage => _statusMessage;
  List<Recording> get records => _peerRecords;
  List<DownloadedRecord> get localRecords => _localRecords;
  double get downloadProgress => _downloadProgress;
  int get downloadDone => _downloadDone;
  int get downloadTotal => _downloadTotal;
  String? get lastError => _lastError;
  PeerProfile? get peer => _peer;
  bool get isConnected => _api != null && _status != SyncStatus.error;
  bool get isDownloading => _status == SyncStatus.downloading;
  int get missingCount => _peerRecords.where((r) => !r.isDownloaded).length;
  int get downloadedCount => _peerRecords.where((r) => r.isDownloaded).length;
  Set<int> get downloadingIds => _downloadingIds;
  String? streamUrl(int _) => null;
  Map<String, String>? get authHeaders => null;

  Future<bool> connectPeer(PeerProfile profile) async {
    _setStatus(SyncStatus.connecting, 'Connexion au pair…');
    _peer = profile;
    await StorageService.setPeer(profile);
    _startKeepAlive();
    try {
      final api = P2pApi(profile);
      if (!await api.ping()) throw const SocketException('Pair indisponible');
      _api = api;
      _lastError = null;
      await refreshLocalRecords();
      await fetchRecords();
      return true;
    } catch (error) {
      _api = null;
      _lastError = error.toString();
      _setStatus(SyncStatus.error, 'Pair hors ligne');
      return false;
    }
  }

  Future<bool> reconnect() async {
    if (_reconnectInFlight) return false;
    final profile = await StorageService.getPeer();
    if (profile == null) {
      _setStatus(SyncStatus.idle, 'Aucun pair lié');
      return false;
    }
    _reconnectInFlight = true;
    try {
      return await connectPeer(profile);
    } finally {
      _reconnectInFlight = false;
    }
  }

  void _startKeepAlive() {
    _keepAliveTimer ??= Timer.periodic(const Duration(minutes: 1), (_) async {
      final api = _api;
      if (_peer == null || _reconnectInFlight) return;
      if (api == null || _status == SyncStatus.error) {
        await reconnect();
        return;
      }
      try {
        if (!await api.ping()) throw const SocketException('Pair indisponible');
        await fetchRecords();
      } catch (error) {
        _api = null;
        _lastError = error.toString();
        _setStatus(SyncStatus.error, 'Pair hors ligne — reconnexion automatique');
      }
    });
  }

  Future<void> fetchRecords() async {
    final api = _api;
    if (api == null) return;
    _setStatus(SyncStatus.syncing, 'Lecture du manifeste…');
    try {
      final records = await api.getManifest();
      await refreshLocalRecords();
      final byHash = {for (final record in _localRecords) record.sha256: record};
      for (final record in records) {
        final local = byHash[record.sha256];
        record.isDownloaded = local != null && File(local.localPath).existsSync();
        record.localPath = record.isDownloaded ? local!.localPath : null;
      }
      _peerRecords = records;
      _setStatus(SyncStatus.done, '${records.length} fichier(s) du pair');
      unawaited(_autoDownloadMissing());
    } catch (error) {
      _lastError = error.toString();
      _setStatus(SyncStatus.error, 'Manifest inaccessible');
    }
  }

  Future<void> refreshLocalRecords() async {
    _localRecords = await StorageService.getDownloadedRecords();
    notifyListeners();
  }

  Future<void> _autoDownloadMissing() async {
    final missing = _peerRecords.where((record) => !record.isDownloaded).toList();
    if (missing.isEmpty) return;
    _downloadTotal = missing.length;
    _downloadDone = 0;
    _downloadProgress = 0;
    _setStatus(SyncStatus.downloading, 'Synchronisation automatique…');
    for (final record in missing) {
      await downloadOne(record);
    }
    _setStatus(SyncStatus.done, 'Synchronisation terminée');
  }

  Future<void> downloadOne(Recording record) async {
    final api = _api;
    if (api == null || _downloadingIds.contains(record.id)) return;
    _downloadingIds.add(record.id);
    notifyListeners();
    try {
      final path = await StorageService.getLocalPath(record.name);
      final part = File('$path.part');
      final offset = await part.exists() ? await part.length() : 0;
      await api.downloadToFile(record, path, offset: offset);
      record.isDownloaded = true;
      record.localPath = path;
      await StorageService.saveDownloadedRecord(DownloadedRecord(
        serverId: record.id,
        sha256: record.sha256,
        name: record.name,
        size: record.size,
        localPath: path,
        downloadedAt: DateTime.now(),
        deviceId: record.deviceId,
      ));
    } catch (error) {
      _lastError = error.toString();
    } finally {
      _downloadingIds.remove(record.id);
      _downloadDone++;
      _downloadProgress =
          _downloadTotal == 0 ? 0 : _downloadDone / _downloadTotal;
      await refreshLocalRecords();
      notifyListeners();
    }
  }

  Future<void> deleteLocal(Recording record) async {
    if (record.localPath != null) {
      try {
        await File(record.localPath!).delete();
      } catch (_) {}
    }
    await StorageService.removeDownloadedRecord(record.id);
    record.isDownloaded = false;
    record.localPath = null;
    await refreshLocalRecords();
    notifyListeners();
  }

  Future<void> deleteFromServer(Recording record) => deleteLocal(record);

  Future<Map<String, dynamic>?> purgeServer() async {
    // There is deliberately no central storage to purge in P2P mode.
    return {'deleted': 0, 'message': 'Aucun serveur de stockage'};
  }

  Future<int> clearAllLocal() async {
    final count = await StorageService.deleteAllLocalFilesAndRegistry();
    for (final record in _peerRecords) {
      record.isDownloaded = false;
      record.localPath = null;
    }
    await refreshLocalRecords();
    notifyListeners();
    return count;
  }

  Future<void> deleteAtSource(Recording _) async {}
  Future<void> purgeAllSourceFolders() async {}

  Future<void> unpair() async {
    _keepAliveTimer?.cancel();
    _keepAliveTimer = null;
    _api = null;
    _peer = null;
    await StorageService.clearPeer();
    _peerRecords = [];
    _setStatus(SyncStatus.idle, 'Aucun pair lié');
  }

  @override
  void dispose() {
    _keepAliveTimer?.cancel();
    _keepAliveTimer = null;
    super.dispose();
  }

  void _setStatus(SyncStatus status, String message) {
    _status = status;
    _statusMessage = message;
    notifyListeners();
  }
}