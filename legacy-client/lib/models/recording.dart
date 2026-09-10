class DeviceInfo {
  final String id;
  final String name;
  final String androidVersion;

  DeviceInfo({required this.id, required this.name, required this.androidVersion});

  factory DeviceInfo.fromJson(Map<String, dynamic> json) => DeviceInfo(
    id:             json['id'] as String? ?? '',
    name:           json['name'] as String? ?? '',
    androidVersion: json['android_version'] as String? ?? '',
  );
}

class Recording {
  final int id;
  final String name;
  final int size;
  final String sha256;
  final double duration;
  final String uploadDate;
  final String creationDate;
  final String path;
  final String deviceId;
  final DeviceInfo? device;

  // Local state
  bool isDownloaded;
  String? localPath;

  Recording({
    required this.id,
    required this.name,
    required this.size,
    required this.sha256,
    required this.duration,
    required this.uploadDate,
    required this.creationDate,
    required this.path,
    required this.deviceId,
    this.device,
    this.isDownloaded = false,
    this.localPath,
  });

  factory Recording.fromJson(Map<String, dynamic> json) {
    final deviceJson = json['device'] as Map<String, dynamic>?;
    return Recording(
      id:           (json['id'] as num).toInt(),
      name:         json['name'] as String? ?? '',
      size:         (json['size'] as num?)?.toInt() ?? 0,
      sha256:       json['sha256'] as String? ?? '',
      duration:     (json['duration'] as num?)?.toDouble() ?? 0.0,
      uploadDate:   json['upload_date'] as String? ?? '',
      creationDate: json['creation_date'] as String? ?? '',
      path:         json['path'] as String? ?? '',
      deviceId:     json['device_id'] as String? ?? '',
      device:       deviceJson != null ? DeviceInfo.fromJson(deviceJson) : null,
    );
  }

  String get deviceName => device?.name ?? deviceId;

  String get sizeFormatted {
    if (size < 1024) return '$size B';
    if (size < 1024 * 1024) return '${(size / 1024).toStringAsFixed(1)} KB';
    return '${(size / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  String get durationFormatted {
    final secs = duration.toInt();
    return '${secs ~/ 60}:${(secs % 60).toString().padLeft(2, '0')}';
  }
}

class DownloadedRecord {
  final int serverId;
  final String sha256;
  final String name;
  final int size;
  final String localPath;
  final DateTime downloadedAt;
  final String deviceId;

  DownloadedRecord({
    required this.serverId,
    required this.sha256,
    required this.name,
    required this.size,
    required this.localPath,
    required this.downloadedAt,
    this.deviceId = '',
  });

  Map<String, dynamic> toJson() => {
    'serverId':    serverId,
    'sha256':      sha256,
    'name':        name,
    'size':        size,
    'localPath':   localPath,
    'downloadedAt': downloadedAt.toIso8601String(),
    'deviceId':    deviceId,
  };

  factory DownloadedRecord.fromJson(Map<String, dynamic> json) => DownloadedRecord(
    serverId:    (json['serverId'] as num).toInt(),
    sha256:      json['sha256'] as String,
    name:        json['name'] as String,
    size:        (json['size'] as num).toInt(),
    localPath:   json['localPath'] as String,
    downloadedAt: DateTime.parse(json['downloadedAt'] as String),
    deviceId:    json['deviceId'] as String? ?? '',
  );
}
