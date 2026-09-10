import 'dart:io';
import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';
import 'package:provider/provider.dart';
import '../models/recording.dart';
import '../services/sync_service.dart';
import '../services/storage_service.dart';
import '../services/update_service.dart';
import 'settings_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _player     = AudioPlayer();
  Recording?        _playing;
  bool              _isPlaying  = false;
  Duration          _position   = Duration.zero;
  Duration          _duration   = Duration.zero;
  double            _volume     = 1.0;
  String            _search     = '';
  bool              _showSearch = false;
  final _searchCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    _player.playerStateStream.listen(
        (s) { if (mounted) setState(() => _isPlaying = s.playing); });
    _player.positionStream.listen(
        (p) { if (mounted) setState(() => _position = p); });
    _player.durationStream.listen(
        (d) { if (mounted && d != null) setState(() => _duration = d); });

    // Auto-check for updates 6s after screen loads
    Future.delayed(const Duration(seconds: 6), () {
      if (mounted) {
        context.read<UpdateService>().checkForUpdate();
      }
    });
  }

  @override
  void dispose() {
    _player.dispose();
    _searchCtrl.dispose();
    super.dispose();
  }

  // ── Playback ────────────────────────────────────────────────────────────────

  Future<void> _play(Recording rec, SyncService sync) async {
    try {
      if (_playing?.id == rec.id) {
        _isPlaying ? await _player.pause() : await _player.play();
        return;
      }
      await _player.stop();
      setState(() {
        _playing  = rec;
        _position = Duration.zero;
        _duration = Duration.zero;
      });
      await _player.setVolume(_volume);

      if (rec.localPath != null && File(rec.localPath!).existsSync()) {
        await _player.setFilePath(rec.localPath!);
      } else {
        if (!sync.isConnected) {
          _snack('Pair hors ligne — réessayez quand il sera connecté',
              error: true);
          return;
        }
        _snack('Téléchargement du fichier avant lecture…');
        await sync.downloadOne(rec);
        if (rec.localPath == null || !File(rec.localPath!).existsSync()) {
          _snack('Téléchargement impossible', error: true);
          return;
        }
        await _player.setFilePath(rec.localPath!);
      }
      await _player.play();
    } catch (e) {
      _snack('Lecture échouée: $e', error: true);
    }
  }

  void _stopPlayer() {
    _player.stop();
    setState(() { _playing = null; _position = Duration.zero; });
  }

  Future<void> _seek(Duration pos) => _player.seek(pos);

  Future<void> _skipBackward() async {
    final target = _position - const Duration(seconds: 10);
    await _player.seek(target < Duration.zero ? Duration.zero : target);
  }

  Future<void> _skipForward() async {
    final target = _position + const Duration(seconds: 10);
    await _player.seek(target > _duration ? _duration : target);
  }

  // ── Snack ───────────────────────────────────────────────────────────────────

  void _snack(String msg, {bool error = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg),
      backgroundColor: error ? Theme.of(context).colorScheme.error : null,
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
    ));
  }

  // ── Confirm ─────────────────────────────────────────────────────────────────

  Future<bool> _confirm(String title, String body,
      {String action = 'Supprimer', bool destructive = true}) async {
    return await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(16)),
            title: Text(title,
                style: const TextStyle(fontWeight: FontWeight.w700)),
            content: Text(body),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Annuler')),
              FilledButton(
                style: destructive
                    ? FilledButton.styleFrom(
                        backgroundColor:
                            Theme.of(context).colorScheme.error)
                    : null,
                onPressed: () => Navigator.pop(context, true),
                child: Text(action),
              ),
            ],
          ),
        ) ??
        false;
  }

  // ── Delete / source menu ────────────────────────────────────────────────────

  void _showDeleteMenu(Recording rec, SyncService sync) {
    final theme = Theme.of(context);
    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(height: 10),
            Container(
              width: 36, height: 4,
              decoration: BoxDecoration(
                  color: theme.colorScheme.outlineVariant,
                  borderRadius: BorderRadius.circular(2)),
            ),
            const SizedBox(height: 12),
            // Header
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
              child: Row(children: [
                Icon(Icons.audio_file_outlined,
                    size: 16, color: theme.colorScheme.onSurfaceVariant),
                const SizedBox(width: 8),
                Flexible(
                  child: Text(
                    rec.name,
                    style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ]),
            ),
            Divider(
                height: 16,
                indent: 16,
                endIndent: 16,
                color: theme.colorScheme.outlineVariant.withOpacity(0.5)),
            if (rec.isDownloaded)
              ListTile(
                leading: Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(8),
                    color: theme.colorScheme.surfaceContainerHigh,
                  ),
                  child: Icon(Icons.phone_android_outlined,
                      size: 18, color: theme.colorScheme.onSurface),
                ),
                title: const Text('Supprimer du téléphone (local)'),
                subtitle: const Text('Retire le fichier de ce téléphone'),
                onTap: () async {
                  Navigator.pop(context);
                  if (_playing?.id == rec.id) _stopPlayer();
                  await sync.deleteLocal(rec);
                  _snack('Supprimé du téléphone');
                },
              ),
            if (sync.isConnected)
              ListTile(
                leading: Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(8),
                    color: theme.colorScheme.errorContainer.withOpacity(0.5),
                  ),
                  child: Icon(Icons.cloud_off_outlined,
                      size: 18, color: theme.colorScheme.error),
                ),
                title: const Text('Supprimer de ce téléphone'),
                subtitle:
                    const Text('Suppression définitive — ne peut pas être annulée'),
                onTap: () async {
                  Navigator.pop(context);
                  final ok = await _confirm(
                    'Supprimer de ce téléphone',
                    '« ${rec.name} » sera supprimé uniquement de ce téléphone.',
                  );
                  if (!ok) return;
                  if (_playing?.id == rec.id) _stopPlayer();
                  await sync.deleteFromServer(rec);
                  _snack('Supprimé de ce téléphone');
                },
              ),
            if (sync.isConnected)
              ListTile(
                leading: Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(8),
                    color: const Color(0xFFF59E0B).withOpacity(0.15),
                  ),
                  child: const Icon(Icons.smartphone_outlined,
                      size: 18, color: Color(0xFFD97706)),
                ),
                title: const Text('Supprimer du téléphone source'),
                subtitle: Text(
                    'Demande au téléphone ${rec.deviceId} de supprimer ce fichier',
                    maxLines: 2),
                onTap: () async {
                  Navigator.pop(context);
                  final ok = await _confirm(
                    'Supprimer du téléphone source',
                    'CallSync ne supprime jamais automatiquement le dossier source.',
                    action: 'Envoyer',
                    destructive: false,
                  );
                  if (!ok) return;
                  await sync.deleteAtSource(rec);
                  _snack('Aucune suppression distante effectuée');
                },
              ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  // ── Filter ──────────────────────────────────────────────────────────────────

  List<Recording> _filtered(List<Recording> all) {
    if (_search.isEmpty) return all;
    final q = _search.toLowerCase();
    return all.where((r) =>
        r.name.toLowerCase().contains(q) ||
        r.deviceId.toLowerCase().contains(q)).toList();
  }

  // ── Build ───────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final sync   = context.watch<SyncService>();
    final update = context.watch<UpdateService>();
    final theme  = Theme.of(context);
    final recs   = _filtered(sync.records);
    final miss   = sync.missingCount;

    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      appBar: _buildAppBar(theme, sync),
      body: Column(
        children: [
          _UpdateBanner(service: update),
          _StatusBar(sync: sync),
          Expanded(
            child: !sync.isConnected && sync.records.isEmpty
                ? _buildNotConnected(theme)
                : recs.isEmpty
                    ? _buildEmpty(theme)
                    : ListView.builder(
                        padding: const EdgeInsets.fromLTRB(14, 8, 14, 120),
                        itemCount: recs.length,
                        itemBuilder: (ctx, i) {
                          final rec = recs[i];
                          return Padding(
                            padding: const EdgeInsets.only(bottom: 8),
                            child: _RecordingTile(
                              rec: rec,
                              isPlaying:
                                  _playing?.id == rec.id && _isPlaying,
                              isPaused:
                                  _playing?.id == rec.id && !_isPlaying,
                              isDownloading:
                                  sync.downloadingIds.contains(rec.id),
                              onTap: () => _play(rec, sync),
                              onLongPress: () =>
                                  _showDeleteMenu(rec, sync),
                            ),
                          );
                        },
                      ),
          ),
          if (_playing != null)
            _MiniPlayer(
              rec:       _playing!,
              isPlaying: _isPlaying,
              position:  _position,
              duration:  _duration,
              volume:    _volume,
              onPlay:    () =>
                  _isPlaying ? _player.pause() : _player.play(),
              onStop:    _stopPlayer,
              onSeek:    _seek,
              onSkipBack: _skipBackward,
              onSkipFwd:  _skipForward,
              onVolume:  (v) {
                setState(() => _volume = v);
                _player.setVolume(v);
              },
            ),
        ],
      ),
      floatingActionButton:
          sync.isConnected && miss > 0 && !sync.isDownloading
              ? FloatingActionButton.extended(
                  onPressed: sync.downloadAllMissing,
                  icon: const Icon(Icons.download_rounded),
                  label: Text('Télécharger $miss'),
                  elevation: 3,
                )
              : null,
    );
  }

  PreferredSizeWidget _buildAppBar(ThemeData theme, SyncService sync) {
    return AppBar(
      backgroundColor: theme.colorScheme.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      bottom: PreferredSize(
        preferredSize: const Size.fromHeight(1),
        child: Divider(
            height: 1, thickness: 1,
            color: theme.colorScheme.outlineVariant.withOpacity(0.4)),
      ),
      title: _showSearch
          ? TextField(
              controller: _searchCtrl,
              autofocus: true,
              decoration: const InputDecoration(
                hintText: 'Rechercher…',
                border: InputBorder.none,
              ),
              onChanged: (v) => setState(() => _search = v),
            )
          : Row(children: [
              Container(
                width: 28, height: 28,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(8),
                  gradient: LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [
                      theme.colorScheme.primary,
                      theme.colorScheme.tertiary,
                    ],
                  ),
                ),
                child: const Icon(Icons.graphic_eq_rounded,
                    size: 16, color: Colors.white),
              ),
              const SizedBox(width: 10),
              Text('CallSync',
                  style: theme.textTheme.titleLarge?.copyWith(
                      fontWeight: FontWeight.w800,
                      letterSpacing: -0.5)),
              const SizedBox(width: 10),
              if (sync.isConnected)
                _StatusDot(connected: true)
              else
                _StatusDot(connected: false),
            ]),
      actions: [
        IconButton(
          icon: Icon(_showSearch ? Icons.close : Icons.search_rounded),
          onPressed: () => setState(() {
            _showSearch = !_showSearch;
            if (!_showSearch) {
              _search = '';
              _searchCtrl.clear();
            }
          }),
        ),
        if (sync.isConnected)
          IconButton(
            icon: const Icon(Icons.refresh_rounded),
            onPressed: sync.isDownloading ? null : sync.fetchRecords,
          ),
        PopupMenuButton<String>(
          icon: const Icon(Icons.more_vert_rounded),
          shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12)),
          onSelected: (v) async {
            if (v == 'settings') {
              await Navigator.push(
                  context,
                  MaterialPageRoute(
                      builder: (_) => const SettingsScreen()));
            } else if (v == 'purge_local') {
              final ok = await _confirm(
                  'Vider le stockage local',
                  'Tous les fichiers téléchargés seront supprimés de ce téléphone. '
                      'Les fichiers restent sur le téléphone source.');
              if (!ok) return;
              if (_playing != null) _stopPlayer();
              final n = await sync.clearAllLocal();
              _snack('$n fichier(s) supprimé(s) du téléphone');
            } else if (v == 'purge_server') {
              final ok = await _confirm(
                  'Vider le stockage pair',
                  'Le mode pair-à-pair ne stocke aucun fichier sur un serveur. '
                  'Cette action ne supprime que les fichiers locaux.',
                  action: 'Vider');
              if (!ok) return;
              if (_playing != null) _stopPlayer();
              await sync.purgeServer();
              _snack('Aucun serveur de stockage à vider');
            } else if (v == 'purge_source') {
              final ok = await _confirm(
                  'Gérer les fichiers source',
                  'CallSync ne supprime jamais automatiquement le dossier source.',
                  action: 'Envoyer les commandes',
                  destructive: true);
              if (!ok) return;
              await sync.purgeAllSourceFolders();
              _snack('Aucune suppression distante effectuée');
            }
          },
          itemBuilder: (_) => [
            PopupMenuItem(
              value: 'settings',
              child: Row(children: const [
                Icon(Icons.settings_outlined, size: 18),
                SizedBox(width: 12),
                Text('Paramètres'),
              ]),
            ),
            if (sync.isConnected) ...[
              const PopupMenuDivider(),
              PopupMenuItem(
                value: 'purge_local',
                child: Row(children: [
                  Icon(Icons.phone_android_outlined,
                      size: 18,
                      color: Theme.of(context).colorScheme.onSurfaceVariant),
                  const SizedBox(width: 12),
                  const Text('Vider le stockage local'),
                ]),
              ),
              PopupMenuItem(
                value: 'purge_server',
                child: Row(children: [
                  Icon(Icons.cloud_off_outlined,
                      size: 18,
                      color: Theme.of(context).colorScheme.error),
                  const SizedBox(width: 12),
                  Text('Vider le stockage pair',
                      style: TextStyle(
                          color: Theme.of(context).colorScheme.error)),
                ]),
              ),
              PopupMenuItem(
                value: 'purge_source',
                child: Row(children: const [
                  Icon(Icons.smartphone_outlined,
                      size: 18, color: Color(0xFFD97706)),
                  SizedBox(width: 12),
                  Text('Gérer les fichiers source',
                      style: TextStyle(color: Color(0xFFD97706))),
                ]),
              ),
            ],
          ],
        ),
      ],
    );
  }

  Widget _buildNotConnected(ThemeData theme) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(40),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 80, height: 80,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(20),
                color: theme.colorScheme.surfaceContainerHigh,
                border: Border.all(
                    color: theme.colorScheme.outlineVariant, width: 1),
              ),
              child: Icon(Icons.cloud_off_outlined,
                  size: 36,
                  color: theme.colorScheme.onSurfaceVariant
                      .withOpacity(0.5)),
            ),
            const SizedBox(height: 20),
            Text('Non connecté',
                style: theme.textTheme.titleMedium
                    ?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 8),
            Text(
              'Liez un téléphone source dans les paramètres\npour synchroniser ses fichiers.',
              style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 28),
            FilledButton.icon(
              icon: const Icon(Icons.settings_outlined, size: 18),
              label: const Text('Ouvrir les paramètres'),
              onPressed: () => Navigator.push(context,
                  MaterialPageRoute(
                      builder: (_) => const SettingsScreen())),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildEmpty(ThemeData theme) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.inbox_outlined,
              size: 48,
              color: theme.colorScheme.onSurfaceVariant.withOpacity(0.4)),
          const SizedBox(height: 12),
          Text('Aucun enregistrement',
              style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant)),
        ],
      ),
    );
  }
}

// ── Status dot ────────────────────────────────────────────────────────────────

class _StatusDot extends StatelessWidget {
  final bool connected;
  const _StatusDot({required this.connected});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        color: connected
            ? const Color(0xFF22C55E).withOpacity(0.12)
            : Theme.of(context).colorScheme.surfaceContainerHigh,
        border: Border.all(
          color: connected
              ? const Color(0xFF22C55E).withOpacity(0.4)
              : Theme.of(context).colorScheme.outlineVariant,
          width: 1,
        ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 6, height: 6,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: connected
                  ? const Color(0xFF22C55E)
                  : Theme.of(context).colorScheme.onSurfaceVariant.withOpacity(0.4),
            ),
          ),
          const SizedBox(width: 5),
          Text(
            connected ? 'En ligne' : 'Hors ligne',
            style: Theme.of(context).textTheme.labelSmall?.copyWith(
              fontWeight: FontWeight.w600,
              color: connected
                  ? const Color(0xFF16A34A)
                  : Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }
}

// ── Status bar ────────────────────────────────────────────────────────────────

class _StatusBar extends StatelessWidget {
  final SyncService sync;
  const _StatusBar({required this.sync});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    if (sync.status == SyncStatus.error) {
      return Container(
        width: double.infinity,
        margin: const EdgeInsets.fromLTRB(14, 10, 14, 0),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(10),
          color: theme.colorScheme.errorContainer.withOpacity(0.8),
          border: Border.all(
              color: theme.colorScheme.error.withOpacity(0.3), width: 1),
        ),
        child: Row(children: [
          Icon(Icons.error_outline,
              size: 16, color: theme.colorScheme.error),
          const SizedBox(width: 8),
          Expanded(
            child: Text(sync.statusMessage,
                style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onErrorContainer,
                    fontWeight: FontWeight.w500)),
          ),
        ]),
      );
    }

    if (sync.status == SyncStatus.downloading) {
      return Column(mainAxisSize: MainAxisSize.min, children: [
        LinearProgressIndicator(
          value: sync.downloadProgress,
          minHeight: 2,
          backgroundColor: theme.colorScheme.surfaceContainerHigh,
        ),
        Container(
          width: double.infinity,
          color: theme.colorScheme.primaryContainer.withOpacity(0.4),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 7),
          child: Row(children: [
            Icon(Icons.downloading_rounded,
                size: 15, color: theme.colorScheme.primary),
            const SizedBox(width: 8),
            Text(
              '${sync.statusMessage} — ${sync.downloadDone}/${sync.downloadTotal}',
              style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.onPrimaryContainer,
                  fontWeight: FontWeight.w500),
            ),
          ]),
        ),
      ]);
    }

    if (sync.status == SyncStatus.connecting ||
        sync.status == SyncStatus.syncing) {
      return Container(
        width: double.infinity,
        color: theme.colorScheme.secondaryContainer.withOpacity(0.35),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 7),
        child: Row(children: [
          SizedBox(
            width: 13, height: 13,
            child: CircularProgressIndicator(
                strokeWidth: 2, color: theme.colorScheme.secondary),
          ),
          const SizedBox(width: 10),
          Text(sync.statusMessage,
              style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.onSecondaryContainer,
                  fontWeight: FontWeight.w500)),
        ]),
      );
    }

    return const SizedBox.shrink();
  }
}

// ── Recording tile ─────────────────────────────────────────────────────────────

class _RecordingTile extends StatelessWidget {
  final Recording rec;
  final bool isPlaying;
  final bool isPaused;
  final bool isDownloading;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  const _RecordingTile({
    required this.rec,
    required this.isPlaying,
    required this.isPaused,
    required this.isDownloading,
    required this.onTap,
    required this.onLongPress,
  });

  @override
  Widget build(BuildContext context) {
    final theme    = Theme.of(context);
    final isActive = isPlaying || isPaused;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: onTap,
        onLongPress: onLongPress,
        child: Container(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(14),
            color: isActive
                ? theme.colorScheme.primary.withOpacity(0.06)
                : theme.colorScheme.surface,
            border: Border.all(
              color: isActive
                  ? theme.colorScheme.primary.withOpacity(0.35)
                  : theme.colorScheme.outlineVariant.withOpacity(0.6),
              width: 1,
            ),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withOpacity(isActive ? 0.06 : 0.03),
                blurRadius: isActive ? 8 : 4,
                offset: const Offset(0, 2),
              ),
            ],
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(13),
            child: IntrinsicHeight(
              child: Row(
                children: [
                  // Active accent strip
                  if (isActive)
                    Container(
                      width: 3,
                      color: theme.colorScheme.primary,
                    ),

                  Expanded(
                    child: Padding(
                      padding: EdgeInsets.fromLTRB(
                          isActive ? 12 : 14, 12, 14, 12),
                      child: Row(
                        children: [
                          // Play/pause button
                          _PlayButton(
                              isPlaying: isPlaying,
                              isPaused: isPaused,
                              isActive: isActive),
                          const SizedBox(width: 13),

                          // Info block
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Text(
                                  rec.name,
                                  style: theme.textTheme.bodyMedium?.copyWith(
                                    fontWeight: FontWeight.w600,
                                    color: isActive
                                        ? theme.colorScheme.primary
                                        : theme.colorScheme.onSurface,
                                    letterSpacing: -0.2,
                                  ),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                                const SizedBox(height: 5),
                                Row(children: [
                                  _MetaChip(
                                    icon: Icons.smartphone_outlined,
                                    label: rec.deviceId,
                                    maxWidth: 110,
                                  ),
                                  const SizedBox(width: 6),
                                  _MetaChip(
                                    icon: Icons.schedule_outlined,
                                    label: _fmtDate(rec.creationDate),
                                  ),
                                ]),
                                const SizedBox(height: 4),
                                Text(
                                  _fmtSize(rec.size),
                                  style: theme.textTheme.labelSmall?.copyWith(
                                      color: theme.colorScheme.onSurfaceVariant
                                          .withOpacity(0.7)),
                                ),
                              ],
                            ),
                          ),

                          const SizedBox(width: 10),

                          // Download badge
                          _DownloadBadge(
                            isDownloaded: rec.isDownloaded,
                            isDownloading: isDownloading,
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  static String _fmtDate(String iso) {
    try {
      final d = DateTime.parse(iso).toLocal();
      return '${d.day.toString().padLeft(2, '0')}/'
          '${d.month.toString().padLeft(2, '0')} '
          '${d.hour.toString().padLeft(2, '0')}:'
          '${d.minute.toString().padLeft(2, '0')}';
    } catch (_) {
      return iso;
    }
  }

  static String _fmtSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
}

class _MetaChip extends StatelessWidget {
  final IconData icon;
  final String label;
  final double? maxWidth;
  const _MetaChip(
      {required this.icon, required this.label, this.maxWidth});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(6),
        color: theme.colorScheme.surfaceContainerHigh,
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(icon,
            size: 11,
            color: theme.colorScheme.onSurfaceVariant.withOpacity(0.8)),
        const SizedBox(width: 3),
        ConstrainedBox(
          constraints: BoxConstraints(maxWidth: maxWidth ?? 130),
          child: Text(
            label,
            style: theme.textTheme.labelSmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
                fontWeight: FontWeight.w500),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
        ),
      ]),
    );
  }
}

class _PlayButton extends StatelessWidget {
  final bool isPlaying;
  final bool isPaused;
  final bool isActive;
  const _PlayButton(
      {required this.isPlaying,
      required this.isPaused,
      required this.isActive});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      width: 44,
      height: 44,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: isActive
            ? theme.colorScheme.primary
            : theme.colorScheme.surfaceContainerHigh,
        border: Border.all(
          color: isActive
              ? theme.colorScheme.primary
              : theme.colorScheme.outlineVariant.withOpacity(0.6),
          width: 1,
        ),
        boxShadow: isActive
            ? [
                BoxShadow(
                  color: theme.colorScheme.primary.withOpacity(0.25),
                  blurRadius: 8,
                  offset: const Offset(0, 2),
                )
              ]
            : null,
      ),
      child: Icon(
        isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded,
        color: isActive
            ? theme.colorScheme.onPrimary
            : theme.colorScheme.onSurfaceVariant,
        size: 22,
      ),
    );
  }
}

class _DownloadBadge extends StatelessWidget {
  final bool isDownloaded;
  final bool isDownloading;
  const _DownloadBadge(
      {required this.isDownloaded, required this.isDownloading});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    if (isDownloading) {
      return SizedBox(
        width: 22,
        height: 22,
        child: CircularProgressIndicator(
            strokeWidth: 2.5, color: theme.colorScheme.primary),
      );
    }
    if (isDownloaded) {
      return Container(
        width: 22,
        height: 22,
        decoration: const BoxDecoration(
            shape: BoxShape.circle, color: Color(0xFF22C55E)),
        child: const Icon(Icons.check_rounded, size: 13, color: Colors.white),
      );
    }
    return Icon(Icons.download_outlined,
        size: 20,
        color: theme.colorScheme.onSurfaceVariant.withOpacity(0.45));
  }
}

// ── Mini player ───────────────────────────────────────────────────────────────

class _MiniPlayer extends StatelessWidget {
  final Recording rec;
  final bool isPlaying;
  final Duration position;
  final Duration duration;
  final double volume;
  final VoidCallback onPlay;
  final VoidCallback onStop;
  final VoidCallback onSkipBack;
  final VoidCallback onSkipFwd;
  final ValueChanged<Duration> onSeek;
  final ValueChanged<double> onVolume;

  const _MiniPlayer({
    required this.rec,
    required this.isPlaying,
    required this.position,
    required this.duration,
    required this.volume,
    required this.onPlay,
    required this.onStop,
    required this.onSkipBack,
    required this.onSkipFwd,
    required this.onSeek,
    required this.onVolume,
  });

  @override
  Widget build(BuildContext context) {
    final theme    = Theme.of(context);
    final progress = duration.inMilliseconds > 0
        ? (position.inMilliseconds / duration.inMilliseconds)
            .clamp(0.0, 1.0)
        : 0.0;

    return Container(
      decoration: BoxDecoration(
        color: theme.colorScheme.surface,
        border: Border(
          top: BorderSide(
              color: theme.colorScheme.outlineVariant.withOpacity(0.6),
              width: 1),
        ),
        boxShadow: [
          BoxShadow(
              color: Colors.black.withOpacity(0.1),
              blurRadius: 16,
              offset: const Offset(0, -4)),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // ── Scrubber ─────────────────────────────────────────────────────
          SliderTheme(
            data: SliderTheme.of(context).copyWith(
              trackHeight: 3,
              thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
              overlayShape:
                  const RoundSliderOverlayShape(overlayRadius: 16),
              activeTrackColor: theme.colorScheme.primary,
              inactiveTrackColor:
                  theme.colorScheme.surfaceContainerHigh,
              thumbColor: theme.colorScheme.primary,
            ),
            child: Slider(
              value: progress,
              onChanged: duration.inMilliseconds > 0
                  ? (v) => onSeek(Duration(
                      milliseconds:
                          (v * duration.inMilliseconds).round()))
                  : null,
            ),
          ),

          // ── Info + controls ──────────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 8, 4),
            child: Row(children: [
              // Waveform icon
              Container(
                width: 36, height: 36,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(10),
                  color: theme.colorScheme.primaryContainer.withOpacity(0.6),
                ),
                child: Icon(Icons.graphic_eq_rounded,
                    size: 20, color: theme.colorScheme.primary),
              ),
              const SizedBox(width: 12),

              // Title + time
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(rec.name,
                        style: theme.textTheme.bodySmall?.copyWith(
                            fontWeight: FontWeight.w700,
                            letterSpacing: -0.2),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis),
                    Text(
                      '${_fmt(position)} / ${_fmt(duration)}',
                      style: theme.textTheme.labelSmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant),
                    ),
                  ],
                ),
              ),

              // Controls
              IconButton(
                icon: const Icon(Icons.replay_10_rounded),
                iconSize: 22,
                onPressed: onSkipBack,
              ),
              IconButton(
                icon: Icon(isPlaying
                    ? Icons.pause_circle_filled_rounded
                    : Icons.play_circle_filled_rounded),
                iconSize: 36,
                color: theme.colorScheme.primary,
                onPressed: onPlay,
              ),
              IconButton(
                icon: const Icon(Icons.forward_10_rounded),
                iconSize: 22,
                onPressed: onSkipFwd,
              ),
              IconButton(
                icon: const Icon(Icons.stop_circle_outlined),
                iconSize: 22,
                onPressed: onStop,
              ),
            ]),
          ),

          // ── Volume row ───────────────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: Row(children: [
              Icon(
                volume == 0
                    ? Icons.volume_off_rounded
                    : volume < 0.5
                        ? Icons.volume_down_rounded
                        : Icons.volume_up_rounded,
                size: 18,
                color: theme.colorScheme.onSurfaceVariant,
              ),
              Expanded(
                child: SliderTheme(
                  data: SliderTheme.of(context).copyWith(
                    trackHeight: 2,
                    thumbShape: const RoundSliderThumbShape(
                        enabledThumbRadius: 5),
                    overlayShape:
                        const RoundSliderOverlayShape(overlayRadius: 12),
                    activeTrackColor: theme.colorScheme.secondary,
                    inactiveTrackColor:
                        theme.colorScheme.surfaceContainerHigh,
                    thumbColor: theme.colorScheme.secondary,
                  ),
                  child: Slider(
                    value: volume,
                    min: 0,
                    max: 2.0,
                    onChanged: onVolume,
                  ),
                ),
              ),
              Container(
                width: 42,
                alignment: Alignment.centerRight,
                child: Text(
                  '${(volume * 100).round()}%',
                  style: theme.textTheme.labelSmall?.copyWith(
                    fontWeight: FontWeight.w600,
                    color: volume > 1.0
                        ? theme.colorScheme.primary
                        : theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ),
            ]),
          ),
        ],
      ),
    );
  }

  static String _fmt(Duration d) {
    final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final s = (d.inSeconds % 60).toString().padLeft(2, '0');
    return '$m:$s';
  }
}

// ── Update banner ─────────────────────────────────────────────────────────────

class _UpdateBanner extends StatelessWidget {
  final UpdateService service;
  const _UpdateBanner({required this.service});

  @override
  Widget build(BuildContext context) {
    final s     = service.status;
    final theme = Theme.of(context);

    if (s == UpdateStatus.idle ||
        s == UpdateStatus.checking ||
        s == UpdateStatus.upToDate) {
      return const SizedBox.shrink();
    }

    final isDownloading = s == UpdateStatus.downloading;
    final isInstalling  = s == UpdateStatus.installing;
    final isAvailable   = s == UpdateStatus.available;
    final isError       = s == UpdateStatus.error;

    return AnimatedContainer(
      duration: const Duration(milliseconds: 300),
      color: isError
          ? theme.colorScheme.errorContainer
          : theme.colorScheme.primaryContainer,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                isError ? Icons.error_outline_rounded : Icons.system_update_rounded,
                size: 18,
                color: isError
                    ? theme.colorScheme.error
                    : theme.colorScheme.primary,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  isError
                      ? 'Mise à jour: ${service.errorMessage}'
                      : isAvailable
                          ? 'Mise à jour v${service.updateInfo?.remoteVersion} disponible'
                          : isDownloading
                              ? 'Téléchargement… ${service.downloadProgress}%'
                              : 'Installation en cours…',
                  style: theme.textTheme.bodySmall?.copyWith(
                    fontWeight: FontWeight.w600,
                    color: isError
                        ? theme.colorScheme.onErrorContainer
                        : theme.colorScheme.onPrimaryContainer,
                  ),
                ),
              ),
              if (isAvailable)
                TextButton(
                  onPressed: service.downloadAndInstall,
                  style: TextButton.styleFrom(
                    padding: const EdgeInsets.symmetric(
                        horizontal: 12, vertical: 4),
                    minimumSize: Size.zero,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    foregroundColor: theme.colorScheme.primary,
                  ),
                  child: const Text('Installer',
                      style: TextStyle(fontWeight: FontWeight.w700)),
                ),
              if (isError)
                IconButton(
                  icon: const Icon(Icons.close, size: 16),
                  onPressed: service.dismiss,
                  color: theme.colorScheme.onErrorContainer,
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(),
                ),
            ],
          ),
          if (isDownloading) ...[
            const SizedBox(height: 6),
            LinearProgressIndicator(
              value: service.downloadProgress / 100.0,
              backgroundColor:
                  theme.colorScheme.primary.withOpacity(0.15),
              valueColor: AlwaysStoppedAnimation<Color>(
                  theme.colorScheme.primary),
              borderRadius: BorderRadius.circular(4),
            ),
          ],
          if (isInstalling) ...[
            const SizedBox(height: 6),
            LinearProgressIndicator(
              backgroundColor:
                  theme.colorScheme.primary.withOpacity(0.15),
              valueColor: AlwaysStoppedAnimation<Color>(
                  theme.colorScheme.primary),
              borderRadius: BorderRadius.circular(4),
            ),
          ],
        ],
      ),
    );
  }
}
