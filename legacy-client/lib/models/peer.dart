import 'dart:convert';

class PeerProfile {
  final String id;
  final String name;
  final String host;
  final int port;
  final String secret;
  final List<String> candidates;

  const PeerProfile({
    required this.id,
    required this.name,
    required this.host,
    required this.port,
    required this.secret,
    this.candidates = const [],
  });

  List<String> get hosts {
    final all = <String>[host, ...candidates]
        .where((value) => value.trim().isNotEmpty)
        .map((value) => value.trim())
        .toSet()
        .toList();
    return all.isEmpty ? [host] : all;
  }

  factory PeerProfile.fromJson(Map<String, dynamic> json) => PeerProfile(
        id: json['id'] as String? ?? '',
        name: json['name'] as String? ?? 'CallSync',
        host: json['host'] as String? ?? '',
        port: (json['port'] as num?)?.toInt() ?? 43821,
        secret: json['secret'] as String? ?? '',
        candidates: (json['candidates'] as List<dynamic>? ?? const [])
            .whereType<String>()
            .toList(),
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'host': host,
        'port': port,
        'secret': secret,
        'candidates': candidates,
      };

  String encode() => base64Url.encode(utf8.encode(jsonEncode(toJson())));

  static PeerProfile? decode(String value) {
    try {
      final normalized = value.trim().replaceAll('callsync://pair/', '');
      final padded = normalized.padRight(
          (normalized.length + 3) ~/ 4 * 4, '=');
      final json = jsonDecode(utf8.decode(base64Url.decode(padded)));
      if (json is! Map<String, dynamic>) return null;
      final profile = PeerProfile.fromJson(json);
      if (profile.host.isEmpty || profile.secret.isEmpty) return null;
      return profile;
    } catch (_) {
      return null;
    }
  }
}