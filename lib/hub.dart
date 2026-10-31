import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'main.dart' show HomeScreen;

/// Home screen: "Utility Hub" - a grid of 5 menus, one per merged app.
class UtilityHub extends StatelessWidget {
  const UtilityHub({super.key});

  static const _channel = MethodChannel('utilityhub/launch');

  static final List<_Tile> _tiles = [
    _Tile('All Media Studio', Icons.movie_filter, const Color(0xFF673AB7), 'media'),
    _Tile('All-in-One Tools', Icons.handyman, const Color(0xFFFF9800), 'tools'),
    _Tile('File Manager Pro', Icons.folder_copy, const Color(0xFF1E88E5), 'files'),
    _Tile('APK Studio', Icons.android, const Color(0xFF2E7D32), 'studio'),
    _Tile('Project Vault', Icons.lock, const Color(0xFF3F51B5), 'vault'),
  ];

  Future<void> _open(BuildContext context, _Tile t) async {
    if (t.key == 'vault') {
      Navigator.of(context)
          .push(MaterialPageRoute(builder: (_) => const HomeScreen()));
      return;
    }
    try {
      await _channel.invokeMethod('open', t.key);
    } on PlatformException catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Cannot open ${t.label}: ${e.message}')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: cs.surface,
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 16, 12, 0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Padding(
                padding: EdgeInsets.fromLTRB(8, 8, 8, 20),
                child: Text('Utility Hub',
                    style: TextStyle(fontSize: 32, fontWeight: FontWeight.w400)),
              ),
              Expanded(
                child: GridView.count(
                  crossAxisCount: 3,
                  mainAxisSpacing: 12,
                  crossAxisSpacing: 12,
                  childAspectRatio: 0.95,
                  padding: const EdgeInsets.all(4),
                  children: [
                    for (final t in _tiles)
                      Material(
                        color: cs.surfaceContainerLow,
                        elevation: 2,
                        shadowColor: Colors.black38,
                        borderRadius: BorderRadius.circular(18),
                        child: InkWell(
                          borderRadius: BorderRadius.circular(18),
                          onTap: () => _open(context, t),
                          child: Padding(
                            padding: const EdgeInsets.all(8),
                            child: Column(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                Icon(t.icon, size: 44, color: t.color),
                                const SizedBox(height: 12),
                                Text(t.label,
                                    textAlign: TextAlign.center,
                                    style: const TextStyle(
                                        fontSize: 14, fontWeight: FontWeight.w600)),
                              ],
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Tile {
  final String label;
  final IconData icon;
  final Color color;
  final String key;
  _Tile(this.label, this.icon, this.color, this.key);
}
