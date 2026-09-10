import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:path_provider/path_provider.dart';
import 'package:open_filex/open_filex.dart';

// ── State ─────────────────────────────────────────────────────────────────────

enum UpdateStatus { idle, checking, upToDate, available, downloading, installing, error }

class UpdateInfo {
  final String remoteVersion;
  final String downloadUrl;
  final String? releaseNotes;
  const UpdateInfo({
    required this.remoteVersion,
    required this.downloadUrl,
    this.releaseNotes,
  });
}

// ── Service ───────────────────────────────────────────────────────────────────

class UpdateService extends ChangeNotifier {
  static const String _githubRepo   = 'ferelking242/call-sync-client';
  static const String _releasesApi  =
      'https://api.github.com/repos/$_githubRepo/releases/latest';

  UpdateStatus _status     = UpdateStatus.idle;
  UpdateInfo?  _updateInfo;
  int          _downloadProgress = 0;
  String?      _errorMessage;

  UpdateStatus get status          => _status;
  UpdateInfo?  get updateInfo      => _updateInfo;
  int          get downloadProgress=> _downloadProgress;
  String?      get errorMessage    => _errorMessage;
  bool         get hasUpdate       => _status == UpdateStatus.available;

  // ── Check ──────────────────────────────────────────────────────────────────

  Future<void> checkForUpdate() async {
    _status = UpdateStatus.checking;
    _errorMessage = null;
    notifyListeners();

    try {
      final resp = await http.get(
        Uri.parse(_releasesApi),
        headers: {
          'Accept': 'application/vnd.github.v3+json',
          'User-Agent': 'CallSyncClient',
        },
      ).timeout(const Duration(seconds: 15));

      if (resp.statusCode != 200) {
        _setError('HTTP ${resp.statusCode}');
        return;
      }

      final json       = jsonDecode(resp.body) as Map<String, dynamic>;
      final tagName    = json['tag_name'] as String? ?? '';
      final remoteVer  = tagName.startsWith('v') ? tagName.substring(1) : tagName;

      // Find APK asset
      final assets = (json['assets'] as List<dynamic>? ?? []);
      String? apkUrl;
      for (final asset in assets) {
        final name = asset['name'] as String? ?? '';
        if (name.endsWith('.apk')) {
          apkUrl = asset['browser_download_url'] as String?;
          break;
        }
      }

      if (apkUrl == null) {
        _setError('Aucun APK dans la release $remoteVer');
        return;
      }

      final pkgInfo    = await PackageInfo.fromPlatform();
      final currentVer = pkgInfo.version;

      if (_isNewer(remoteVer, currentVer)) {
        _updateInfo = UpdateInfo(
          remoteVersion: remoteVer,
          downloadUrl:   apkUrl,
          releaseNotes:  json['body'] as String?,
        );
        _status = UpdateStatus.available;
      } else {
        _status = UpdateStatus.upToDate;
      }
      notifyListeners();
    } catch (e) {
      _setError(e.toString());
    }
  }

  // ── Download + Install ────────────────────────────────────────────────────

  Future<void> downloadAndInstall() async {
    final info = _updateInfo;
    if (info == null) return;

    _status          = UpdateStatus.downloading;
    _downloadProgress = 0;
    notifyListeners();

    try {
      final tmpDir = await getTemporaryDirectory();
      final apkFile = File('${tmpDir.path}/callsync_update.apk');

      final request  = http.Request('GET', Uri.parse(info.downloadUrl));
      final response = await request.send().timeout(const Duration(minutes: 5));

      if (response.statusCode != 200) {
        _setError('Téléchargement échoué: HTTP ${response.statusCode}');
        return;
      }

      final total  = response.contentLength ?? 0;
      var received = 0;
      final sink   = apkFile.openWrite();

      await for (final chunk in response.stream) {
        sink.add(chunk);
        received += chunk.length;
        if (total > 0) {
          final pct = ((received / total) * 100).round();
          if (pct != _downloadProgress) {
            _downloadProgress = pct;
            notifyListeners();
          }
        }
      }
      await sink.flush();
      await sink.close();

      _status = UpdateStatus.installing;
      notifyListeners();

      final result = await OpenFilex.open(apkFile.path);
      if (result.type != ResultType.done) {
        _setError('Impossible d\'ouvrir l\'APK: ${result.message}');
      }
    } catch (e) {
      _setError('Erreur: $e');
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  /// Returns true if [remote] is strictly newer than [current].
  bool _isNewer(String remote, String current) {
    final r = remote.split('.').map(int.tryParse).toList();
    final c = current.split('.').map(int.tryParse).toList();
    final len = r.length > c.length ? r.length : c.length;
    for (var i = 0; i < len; i++) {
      final rv = i < r.length ? (r[i] ?? 0) : 0;
      final cv = i < c.length ? (c[i] ?? 0) : 0;
      if (rv > cv) return true;
      if (rv < cv) return false;
    }
    return false;
  }

  void _setError(String msg) {
    _errorMessage = msg;
    _status       = UpdateStatus.error;
    notifyListeners();
  }

  void dismiss() {
    if (_status == UpdateStatus.error || _status == UpdateStatus.upToDate) {
      _status = UpdateStatus.idle;
      notifyListeners();
    }
  }
}
