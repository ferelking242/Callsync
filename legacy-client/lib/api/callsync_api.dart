import 'dart:convert';
import 'dart:io';
import 'package:http/http.dart' as http;
import '../models/recording.dart';

class CallSyncApi {
  final String baseUrl;
  final String token;

  CallSyncApi({required String baseUrl, required this.token})
      : baseUrl = baseUrl.replaceAll(RegExp(r'/+$'), '');

  static String _clean(String url) => url.replaceAll(RegExp(r'/+$'), '');

  Map<String, String> get _headers => {
    'Authorization': 'Bearer $token',
    'Content-Type':  'application/json',
  };

  // ── Health ────────────────────────────────────────────────────────────────

  Future<bool> checkHealth() async {
    try {
      final r = await http.get(Uri.parse('$baseUrl/health'))
          .timeout(const Duration(seconds: 10));
      return r.statusCode == 200;
    } catch (_) {
      return false;
    }
  }

  // ── Auth ──────────────────────────────────────────────────────────────────

  static Future<String?> login(String baseUrl, String username, String password) async {
    final url = _clean(baseUrl);
    try {
      final r = await http.post(
        Uri.parse('$url/login'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({'username': username, 'password': password}),
      ).timeout(const Duration(seconds: 15));
      if (r.statusCode == 200) {
        return (jsonDecode(r.body) as Map<String, dynamic>)['token'] as String?;
      }
      return null;
    } catch (_) {
      return null;
    }
  }

  // ── Records ───────────────────────────────────────────────────────────────

  Future<List<Recording>> getRecords() async {
    final r = await http.get(Uri.parse('$baseUrl/records'), headers: _headers)
        .timeout(const Duration(seconds: 30));
    if (r.statusCode != 200) throw Exception('HTTP ${r.statusCode}');
    return (jsonDecode(r.body) as List)
        .map((j) => Recording.fromJson(j as Map<String, dynamic>))
        .toList();
  }

  // ── Download ──────────────────────────────────────────────────────────────

  // Uses /download/{id} (not /stream): triggers server-side auto-delete after serving.
  Future<void> downloadToFile(int recordId, String savePath) async {
    final r = await http.get(Uri.parse('$baseUrl/download/$recordId'), headers: _headers)
        .timeout(const Duration(minutes: 5));
    if (r.statusCode != 200) throw Exception('HTTP ${r.statusCode}');
    await File(savePath).writeAsBytes(r.bodyBytes);
  }

  // ── Delete single record (server-side) ───────────────────────────────────

  Future<void> deleteRecord(int recordId) async {
    final r = await http.delete(Uri.parse('$baseUrl/record/$recordId'), headers: _headers)
        .timeout(const Duration(seconds: 15));
    if (r.statusCode != 200 && r.statusCode != 204) throw Exception('HTTP ${r.statusCode}');
  }

  // ── Purge all records on server ───────────────────────────────────────────

  Future<Map<String, dynamic>> purgeAll() async {
    final r = await http.delete(Uri.parse('$baseUrl/purge-all'), headers: _headers)
        .timeout(const Duration(seconds: 30));
    if (r.statusCode != 200) throw Exception('HTTP ${r.statusCode}');
    return jsonDecode(r.body) as Map<String, dynamic>;
  }

  // ── Stream URL (for just_audio) ───────────────────────────────────────────

  String streamUrl(int recordId) => '$baseUrl/stream/$recordId';

  Map<String, String> get authHeaders => {'Authorization': 'Bearer $token'};

  // ── Delete-at-source commands ─────────────────────────────────────────────

  Future<void> requestDeleteAtSource(String deviceId, List<String> sha256List) async {
    try {
      await http.post(
        Uri.parse('$baseUrl/delete-commands'),
        headers: _headers,
        body: jsonEncode({'device_id': deviceId, 'sha256_list': sha256List}),
      ).timeout(const Duration(seconds: 15));
    } catch (_) {
      // Endpoint may not exist — silently ignore
    }
  }
}
