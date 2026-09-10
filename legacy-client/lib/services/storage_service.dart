import 'dart:convert';
import 'dart:io';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/peer.dart';
import '../models/recording.dart';

class StorageService {
  static const _keyPeer = 'paired_peer_v1';
  static const _keyDownloaded = 'downloaded_records_v2';

  static Future<PeerProfile?> getPeer() async {
    final p = await SharedPreferences.getInstance();
    final raw = p.getString(_keyPeer);
    if (raw == null || raw.isEmpty) return null;
    try {
      return PeerProfile.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (_) {
      return null;
    }
  }

  static Future<void> setPeer(PeerProfile peer) async {
    final p = await SharedPreferences.getInstance();
    await p.setString(_keyPeer, jsonEncode(peer.toJson()));
  }

  static Future<void> clearPeer() async {
    final p = await SharedPreferences.getInstance();
    await p.remove(_keyPeer);
  }

  static Future<List<DownloadedRecord>> getDownloadedRecords() async {
    final p = await SharedPreferences.getInstance();
    final raw = p.getString(_keyDownloaded);
    if (raw == null || raw.isEmpty) return [];
    try {
      return (jsonDecode(raw) as List)
          .map((j) => DownloadedRecord.fromJson(j as Map<String, dynamic>))
          .toList();
    } catch (_) {
      return [];
    }
  }

  static Future<void> saveDownloadedRecord(DownloadedRecord rec) async {
    final p = await SharedPreferences.getInstance();
    final list = await getDownloadedRecords();
    list.removeWhere((r) => r.serverId == rec.serverId);
    list.add(rec);
    await p.setString(
        _keyDownloaded, jsonEncode(list.map((r) => r.toJson()).toList()));
  }

  static Future<void> removeDownloadedRecord(int serverId) async {
    final p = await SharedPreferences.getInstance();
    final list = await getDownloadedRecords();
    list.removeWhere((r) => r.serverId == serverId);
    await p.setString(
        _keyDownloaded, jsonEncode(list.map((r) => r.toJson()).toList()));
  }

  static Future<void> clearAllDownloads() async {
    final p = await SharedPreferences.getInstance();
    await p.remove(_keyDownloaded);
  }

  static Future<int> deleteAllLocalFilesAndRegistry() async {
    final records = await getDownloadedRecords();
    var deleted = 0;
    for (final r in records) {
      try {
        final file = File(r.localPath);
        if (file.existsSync()) {
          await file.delete();
          deleted++;
        }
      } catch (_) {}
    }
    try {
      final dir = await getRecordingsDir();
      if (await dir.exists()) {
        await for (final entry in dir.list()) {
          await entry.delete(recursive: true);
        }
      }
    } catch (_) {}
    await clearAllDownloads();
    return deleted;
  }

  static Future<Directory> getRecordingsDir() async {
    final appDir = await getApplicationDocumentsDirectory();
    final dir = Directory('${appDir.path}/recordings');
    if (!await dir.exists()) await dir.create(recursive: true);
    return dir;
  }

  static Future<String> getLocalPath(String filename) async {
    final dir = await getRecordingsDir();
    final safeName = filename.replaceAll(RegExp(r'[/\\]'), '_');
    return '${dir.path}/$safeName';
  }

  static Future<int> getLocalStorageBytes() async {
    final dir = await getRecordingsDir();
    if (!await dir.exists()) return 0;
    var total = 0;
    await for (final entry in dir.list(recursive: true)) {
      if (entry is File) total += await entry.length();
    }
    return total;
  }

  static String formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) {
      return '${(bytes / 1024).toStringAsFixed(1)} KB';
    }
    if (bytes < 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    }
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB';
  }
}