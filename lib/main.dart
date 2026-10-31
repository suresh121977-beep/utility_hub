import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';
import 'ai.dart';
import 'bundle.dart';
import 'github.dart';
import 'hub.dart';
import 'store.dart';

void main() => runApp(const VaultApp());

/// GitHub-style "new file" dialog: a path/name box and a content box.
/// Returns (path, content) or null if cancelled.
Future<(String, String)?> askNewFile(BuildContext context,
    {required bool Function(String) exists}) {
  final nameC = TextEditingController();
  final bodyC = TextEditingController();
  String? err;
  return showDialog<(String, String)>(
    context: context,
    builder: (ctx) => StatefulBuilder(
      builder: (ctx, setS) => AlertDialog(
        title: const Text('Create new file'),
        content: SizedBox(
          width: double.maxFinite,
          child: SingleChildScrollView(
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              TextField(
                controller: nameC,
                autofocus: true,
                autocorrect: false,
                enableSuggestions: false,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
                decoration: InputDecoration(
                  labelText: 'Name your file (with path)',
                  hintText: 'lib/screens/home.dart',
                  helperText: 'Use / to create folders, like on GitHub',
                  errorText: err,
                  border: const OutlineInputBorder(),
                ),
                onChanged: (_) {
                  if (err != null) setS(() => err = null);
                },
              ),
              const SizedBox(height: 12),
              TextField(
                controller: bodyC,
                minLines: 8,
                maxLines: 12,
                keyboardType: TextInputType.multiline,
                autocorrect: false,
                enableSuggestions: false,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                decoration: const InputDecoration(
                    hintText: 'Paste the file content here',
                    border: OutlineInputBorder()),
              ),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  icon: const Icon(Icons.content_paste),
                  label: const Text('Paste from clipboard'),
                  onPressed: () async {
                    final d = await Clipboard.getData(Clipboard.kTextPlain);
                    if (d?.text != null) bodyC.text = d!.text!;
                  },
                ),
              ),
            ]),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () {
              final path = normalizePath(nameC.text);
              if (path == null) {
                setS(() => err = 'Enter a valid file name, e.g. lib/main.dart');
                return;
              }
              if (exists(path) && err == null) {
                setS(() => err = 'Already exists. Tap Create again to overwrite.');
                return;
              }
              Navigator.pop(ctx, (path, bodyC.text));
            },
            child: const Text('Create file'),
          ),
        ],
      ),
    ),
  );
}

/// Cleans a typed path. Returns null if it is empty or unsafe.
String? normalizePath(String raw) {
  final parts = raw
      .replaceAll('\\', '/')
      .split('/')
      .map((s) => s.trim())
      .where((s) => s.isNotEmpty && s != '.')
      .toList();
  if (parts.isEmpty || parts.contains('..')) return null;
  return parts.join('/');
}

Future<String?> askPaste(BuildContext context, String path) {
  final c = TextEditingController();
  return showDialog<String>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text('Paste $path'),
      content: SizedBox(
        width: double.maxFinite,
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          TextField(
            controller: c,
            autofocus: true,
            minLines: 8,
            maxLines: 12,
            keyboardType: TextInputType.multiline,
            style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
            decoration: const InputDecoration(
                hintText: 'Paste the full file here', border: OutlineInputBorder()),
          ),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              icon: const Icon(Icons.content_paste),
              label: const Text('Paste from clipboard'),
              onPressed: () async {
                final d = await Clipboard.getData(Clipboard.kTextPlain);
                if (d?.text != null) c.text = d!.text!;
              },
            ),
          ),
        ]),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
        FilledButton(
            onPressed: () => Navigator.pop(ctx, c.text.isEmpty ? null : c.text),
            child: const Text('Save')),
      ],
    ),
  );
}

class VaultApp extends StatelessWidget {
  const VaultApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Utility Hub',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
        home: const UtilityHub(),
      );
}

Future<String?> askText(BuildContext context, String title,
    {String initial = '', int lines = 1, String? hint}) {
  final c = TextEditingController(text: initial);
  return showDialog<String>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: TextField(
        controller: c,
        autofocus: true,
        minLines: lines,
        maxLines: lines == 1 ? 1 : 8,
        decoration: InputDecoration(hintText: hint),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
        FilledButton(onPressed: () => Navigator.pop(ctx, c.text.trim()), child: const Text('OK')),
      ],
    ),
  );
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  List<String> names = [];
  final Set<String> selected = {};
  bool loading = true;
  bool searching = false;
  List<SearchHit>? hits;
  final searchCtl = TextEditingController();
  Timer? _debounce;

  bool get selecting => selected.isNotEmpty;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    searchCtl.dispose();
    super.dispose();
  }

  Future<void> _reload() async {
    final n = await Store.list();
    if (!mounted) return;
    setState(() {
      names = n;
      selected.retainAll(n);
      loading = false;
    });
  }

  void _snack(String s) {
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(s)));
  }

  void _toggle(String n) =>
      setState(() => selected.contains(n) ? selected.remove(n) : selected.add(n));

  void _onQuery(String q) {
    _debounce?.cancel();
    final query = q.trim();
    if (query.isEmpty) {
      setState(() {
        hits = null;
        searching = false;
      });
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 400), () async {
      if (mounted) setState(() => searching = true);
      final h = await Store.search(query);
      if (!mounted || searchCtl.text.trim() != query) return;
      setState(() {
        hits = h;
        searching = false;
      });
    });
  }

  Future<void> _openRepos(bool deleteMode) async {
    await Navigator.push(
        context, MaterialPageRoute(builder: (_) => GitHubReposScreen(deleteMode: deleteMode)));
    await _reload();
  }

  Future<void> _open(String name) async {
    await Navigator.push(context, MaterialPageRoute(builder: (_) => ProjectScreen(name: name)));
    _reload();
  }

  Future<void> _import() async {
    final r = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: ['zip', 'txt'],
        allowMultiple: true,
        withData: true);
    if (r == null || r.files.isEmpty) return;
    final found = <Project>[];
    final errors = <String>[];
    for (final f in r.files) {
      final bytes = f.bytes;
      if (bytes == null) continue;
      try {
        found.addAll(projectsFromAny(f.name, bytes));
      } catch (e) {
        errors.add(f.name);
      }
    }
    if (errors.isNotEmpty) _snack('Could not read: ${errors.join(', ')}');
    if (found.isEmpty) {
      if (errors.isEmpty) _snack('No project files found in the selection.');
      return;
    }
    if (!mounted) return;
    final chosen = await Navigator.push<List<Project>>(
        context, MaterialPageRoute(builder: (_) => ImportPreviewScreen(projects: found)));
    if (chosen == null || chosen.isEmpty) return;
    for (final p in chosen) {
      if (names.contains(p.name)) {
        final old = await Store.load(p.name);
        final gone = <String>{...old.text.keys, ...old.bin.keys, ...old.removed}
          ..removeAll([...p.text.keys, ...p.bin.keys]);
        p.removed.addAll(gone);
      }
      await Store.save(p);
    }
    await _reload();
    _snack('Imported ${chosen.length} app(s)');
  }

  List<String> _targets() => selecting ? names.where(selected.contains).toList() : names;

  Future<void> _saveFile(String name, List<int> data, String title) async {
    try {
      final saved = await FilePicker.platform.saveFile(
        dialogTitle: title,
        fileName: name,
        bytes: Uint8List.fromList(data),
      );
      _snack(saved == null ? 'Cancelled' : 'Saved to your folder');
    } catch (e) {
      _snack('Save failed: $e');
    }
  }

  Future<void> _exportBundle() async {
    final t = _targets();
    if (t.isEmpty) {
      _snack('Nothing to export yet');
      return;
    }
    final ps = <Project>[];
    for (final n in t) {
      ps.add(await Store.load(n));
    }
    final day = DateTime.now().toIso8601String().substring(0, 10);
    await _saveFile('ProjectVault-$day.bundle.txt', utf8.encode(makeVaultBundle(ps)),
        'Choose a folder for the bundle');
  }

  Future<void> _exportZip() async {
    final t = _targets();
    if (t.isEmpty) {
      _snack('Nothing to export yet');
      return;
    }
    final day = DateTime.now().toIso8601String().substring(0, 10);
    await _saveFile('ProjectVault-$day.zip', await Store.backupAll(only: t),
        'Choose a folder for the zip');
  }

  Future<void> _deleteSelected() async {
    final t = names.where(selected.contains).toList();
    if (t.isEmpty) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Delete ${t.length} app(s)?'),
        content: const Text('This removes the copies saved in this app only. Your GitHub repos are not touched.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Delete')),
        ],
      ),
    );
    if (ok != true) return;
    for (final n in t) {
      await Store.delete(n);
    }
    selected.clear();
    await _reload();
    _snack('Deleted ${t.length} app(s)');
  }

  Future<void> _uploadSelected() async {
    final token = await Settings.get('gh');
    if (token.isEmpty) {
      _snack('Add your GitHub token in Settings first.');
      return;
    }
    final log = ValueNotifier<String>('');
    final done = ValueNotifier<bool>(false);
    void add(String s) => log.value = log.value.isEmpty ? s : '${log.value}\n$s';
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        title: const Text('Uploading to GitHub'),
        content: SizedBox(
          width: double.maxFinite,
          height: 260,
          child: ValueListenableBuilder<String>(
            valueListenable: log,
            builder: (_, v, __) => SingleChildScrollView(
              reverse: true,
              child: SelectableText(v, style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
            ),
          ),
        ),
        actions: [
          ValueListenableBuilder<bool>(
            valueListenable: done,
            builder: (_, d, __) =>
                TextButton(onPressed: d ? () => Navigator.pop(ctx) : null, child: const Text('Close')),
          ),
        ],
      ),
    );
    for (final n in _targets()) {
      try {
        add('== $n ==');
        final p = await Store.load(n);
        if (ensureWorkflow(p)) {
          await Store.save(p);
          add('Added build-apk.yml');
        }
        add(await GitHub(token, add).upload(p, 'Update from Project Vault'));
        p.removed.clear();
        await Store.save(p, backup: false);
      } catch (e) {
        add('Error: ${e.toString().replaceFirst('Exception: ', '')}');
      }
    }
    add('Finished. Open an app and tap Build APK to build it.');
    done.value = true;
  }

  Widget _projectList() {
    if (names.isEmpty) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(32),
          child: Text(
              'No apps yet. Tap Import and select one or many project zips, or a bundle file.',
              textAlign: TextAlign.center),
        ),
      );
    }
    final q = searchCtl.text.trim().toLowerCase();
    final shown = q.isEmpty ? names : names.where((n) => n.toLowerCase().contains(q)).toList();
    if (shown.isEmpty) return const Center(child: Text('No app with that name'));
    return ListView.builder(
      itemCount: shown.length,
      itemBuilder: (_, i) {
        final n = shown[i];
        final sel = selected.contains(n);
        return ListTile(
          leading: selecting
              ? Checkbox(value: sel, onChanged: (_) => _toggle(n))
              : const Icon(Icons.folder_zip_outlined),
          title: Text(n),
          trailing: selecting ? null : const Icon(Icons.chevron_right),
          selected: sel,
          onTap: () => selecting ? _toggle(n) : _open(n),
          onLongPress: () => _toggle(n),
        );
      },
    );
  }

  Widget _results() {
    final h = hits;
    if (h == null) return const SizedBox.shrink();
    if (h.isEmpty) return const Center(child: Text('No matches'));
    return ListView.builder(
      itemCount: h.length,
      itemBuilder: (_, i) {
        final x = h[i];
        final isApp = x.path.isEmpty;
        return ListTile(
          leading: Icon(isApp ? Icons.folder_zip_outlined : Icons.insert_drive_file_outlined),
          title: Text(isApp ? x.project : x.path),
          subtitle: Text(
            isApp ? 'App' : (x.snippet == null ? x.project : '${x.project} • ${x.snippet}'),
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
          onTap: () => _open(x.project),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final showResults = searchCtl.text.trim().isNotEmpty;
    return Scaffold(
      appBar: AppBar(
        leading: selecting
            ? IconButton(icon: const Icon(Icons.close), onPressed: () => setState(selected.clear))
            : null,
        title: Text(selecting ? '${selected.length} selected' : 'Project Vault'),
        actions: selecting
            ? [
                IconButton(
                    tooltip: 'Delete',
                    icon: const Icon(Icons.delete_outline),
                    onPressed: _deleteSelected),
                IconButton(
                    tooltip: 'Upload to GitHub',
                    icon: const Icon(Icons.cloud_upload),
                    onPressed: _uploadSelected),
                PopupMenuButton<String>(
                  onSelected: (v) {
                    if (v == 'all') {
                      setState(() => selected.addAll(names));
                    } else if (v == 'bundle') {
                      _exportBundle();
                    } else {
                      _exportZip();
                    }
                  },
                  itemBuilder: (_) => const [
                    PopupMenuItem(value: 'all', child: Text('Select all')),
                    PopupMenuItem(value: 'bundle', child: Text('Download as one bundle (txt)')),
                    PopupMenuItem(value: 'zip', child: Text('Download as one zip')),
                  ],
                ),
              ]
            : [
                PopupMenuButton<String>(
                  onSelected: (v) {
                    if (v == 'bundle') {
                      _exportBundle();
                    } else if (v == 'zip') {
                      _exportZip();
                    } else {
                      _openRepos(v == 'ghdelete');
                    }
                  },
                  itemBuilder: (_) => const [
                    PopupMenuItem(value: 'bundle', child: Text('Download all as one bundle (txt)')),
                    PopupMenuItem(value: 'zip', child: Text('Download all as one zip')),
                    PopupMenuDivider(),
                    PopupMenuItem(value: 'ghrename', child: Text('Rename GitHub repository')),
                    PopupMenuItem(value: 'ghdelete', child: Text('Delete GitHub repository')),
                  ],
                ),
                IconButton(
                  icon: const Icon(Icons.settings),
                  onPressed: () => Navigator.push(
                      context, MaterialPageRoute(builder: (_) => const SettingsScreen())),
                ),
              ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _import,
        icon: const Icon(Icons.add),
        label: const Text('Import'),
      ),
      body: loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                  child: TextField(
                    controller: searchCtl,
                    onChanged: (_) => setState(() {}),
                    decoration: InputDecoration(
                      hintText: 'Search app name',
                      prefixIcon: const Icon(Icons.search),
                      suffixIcon: showResults
                          ? IconButton(
                              icon: const Icon(Icons.close),
                              onPressed: () {
                                searchCtl.clear();
                                setState(() {});
                              },
                            )
                          : null,
                      border: const OutlineInputBorder(
                          borderRadius: BorderRadius.all(Radius.circular(28))),
                      contentPadding: const EdgeInsets.symmetric(vertical: 0),
                    ),
                  ),
                ),
                Expanded(child: _projectList()),
              ],
            ),
    );
  }
}

/// Lists your GitHub repositories to delete (one or many) or rename.
class GitHubReposScreen extends StatefulWidget {
  final bool deleteMode;
  const GitHubReposScreen({super.key, required this.deleteMode});
  @override
  State<GitHubReposScreen> createState() => _GitHubReposScreenState();
}

class _GitHubReposScreenState extends State<GitHubReposScreen> {
  List<RepoInfo>? repos;
  final selected = <String>{};
  String filter = '';
  String? error;
  bool busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<GitHub> _gh() async {
    final t = await Settings.get('gh');
    if (t.isEmpty) throw Exception('Add your GitHub token in Settings first.');
    return GitHub(t, (_) {});
  }

  Future<void> _load() async {
    setState(() {
      repos = null;
      error = null;
    });
    try {
      final r = await (await _gh()).listRepos();
      if (!mounted) return;
      setState(() => repos = r);
    } catch (e) {
      if (!mounted) return;
      setState(() => error = e.toString().replaceFirst('Exception: ', ''));
    }
  }

  void _snack(String s) {
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(s)));
  }

  Future<void> _deleteSelected() async {
    final all = repos ?? [];
    final targets = all.where((r) => selected.contains(r.fullName)).toList();
    if (targets.isEmpty) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Delete ${targets.length} repository(s)?'),
        content: SingleChildScrollView(
          child: Text('${targets.map((r) => r.fullName).join('\n')}\n\n'
              'This permanently deletes them from GitHub, with all code and history. '
              'It cannot be undone. The copies saved in this app are not touched.'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    setState(() => busy = true);
    var done = 0;
    final failed = <String>[];
    String? lastErr;
    try {
      final gh = await _gh();
      for (final r in targets) {
        try {
          await gh.deleteRepo(r.fullName);
          done++;
          selected.remove(r.fullName);
        } catch (e) {
          failed.add(r.name);
          lastErr = e.toString().replaceFirst('Exception: ', '');
          if (lastErr.contains('delete_repo')) break;
        }
      }
    } catch (e) {
      lastErr = e.toString().replaceFirst('Exception: ', '');
    }
    if (!mounted) return;
    setState(() => busy = false);
    _snack(failed.isEmpty && lastErr == null
        ? 'Deleted $done repository(s)'
        : 'Deleted $done. Failed: ${failed.join(', ')}. ${lastErr ?? ''}');
    await _load();
  }

  Future<void> _rename(RepoInfo r) async {
    final n = await askText(context, 'Rename ${r.name}', initial: r.name);
    if (n == null || n.isEmpty || n == r.name || !mounted) return;
    final newName = n.replaceAll(RegExp(r'\s+'), '-');
    setState(() => busy = true);
    try {
      await (await _gh()).renameRepo(r.fullName, newName);
      // keep the saved app in sync so the next upload goes to the renamed repo
      final local = r.name;
      if (await Store.exists(local) && !await Store.exists(newName)) {
        if (!mounted) return;
        final sync = await showDialog<bool>(
          context: context,
          builder: (ctx) => AlertDialog(
            title: const Text('Rename the saved app too?'),
            content: Text('The app "$local" in Project Vault uploads to a repo with its own '
                'name. Rename it to "$newName" so the next upload goes to the renamed repo '
                'instead of creating a new one.'),
            actions: [
              TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('No')),
              FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Yes')),
            ],
          ),
        );
        if (sync == true) await Store.rename(local, newName);
      }
      _snack('Renamed to $newName');
    } catch (e) {
      _snack(e.toString().replaceFirst('Exception: ', ''));
    }
    if (!mounted) return;
    setState(() => busy = false);
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final del = widget.deleteMode;
    final all = repos;
    final shown = all == null
        ? <RepoInfo>[]
        : all.where((r) => r.name.toLowerCase().contains(filter)).toList();
    return Scaffold(
      appBar: AppBar(
        title: Text(del ? 'Delete repositories' : 'Rename repository'),
        actions: [IconButton(icon: const Icon(Icons.refresh), onPressed: busy ? null : _load)],
      ),
      body: error != null
          ? Center(child: Padding(padding: const EdgeInsets.all(24), child: Text(error!)))
          : all == null
              ? const Center(child: CircularProgressIndicator())
              : Column(children: [
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                    child: TextField(
                      onChanged: (v) => setState(() => filter = v.trim().toLowerCase()),
                      decoration: const InputDecoration(
                          hintText: 'Search repository name',
                          prefixIcon: Icon(Icons.search),
                          isDense: true,
                          border: OutlineInputBorder()),
                    ),
                  ),
                  if (busy) const LinearProgressIndicator(),
                  Expanded(
                    child: shown.isEmpty
                        ? const Center(child: Text('No repositories'))
                        : ListView(children: [
                            for (final r in shown)
                              del
                                  ? CheckboxListTile(
                                      value: selected.contains(r.fullName),
                                      title: Text(r.name),
                                      subtitle: Text(r.private ? 'Private' : 'Public'),
                                      onChanged: busy
                                          ? null
                                          : (v) => setState(() => v == true
                                              ? selected.add(r.fullName)
                                              : selected.remove(r.fullName)),
                                    )
                                  : ListTile(
                                      leading: const Icon(Icons.edit_outlined),
                                      title: Text(r.name),
                                      subtitle: Text(r.private ? 'Private' : 'Public'),
                                      onTap: busy ? null : () => _rename(r),
                                    ),
                          ]),
                  ),
                ]),
      bottomNavigationBar: del
          ? SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: FilledButton.icon(
                  style: FilledButton.styleFrom(backgroundColor: Colors.red),
                  onPressed: busy || selected.isEmpty ? null : _deleteSelected,
                  icon: const Icon(Icons.delete_forever),
                  label: Text('Delete ${selected.length} selected'),
                ),
              ),
            )
          : null,
    );
  }
}

class ImportPreviewScreen extends StatefulWidget {
  final List<Project> projects;
  const ImportPreviewScreen({super.key, required this.projects});
  @override
  State<ImportPreviewScreen> createState() => _ImportPreviewScreenState();
}

class _ImportPreviewScreenState extends State<ImportPreviewScreen> {
  late final List<bool> sel = List<bool>.filled(widget.projects.length, true);
  late final List<String> names = widget.projects.map((p) => p.name).toList();

  List<Project> _result() {
    final used = <String>{};
    final out = <Project>[];
    for (var i = 0; i < widget.projects.length; i++) {
      if (!sel[i]) continue;
      final base = cleanName(names[i]);
      var name = base;
      var k = 2;
      while (!used.add(name)) {
        name = '$base-$k';
        k++;
      }
      out.add(Project(name, widget.projects[i].text, widget.projects[i].bin));
    }
    return out;
  }

  @override
  Widget build(BuildContext context) {
    final count = sel.where((s) => s).length;
    return Scaffold(
      appBar: AppBar(title: Text('Found ${widget.projects.length} app(s)')),
      body: ListView.builder(
        itemCount: widget.projects.length,
        itemBuilder: (_, i) {
          final p = widget.projects[i];
          final files = [...p.text.keys, ...p.bin.keys]..sort();
          return ExpansionTile(
            leading: Checkbox(
                value: sel[i], onChanged: (v) => setState(() => sel[i] = v ?? false)),
            title: Text(names[i]),
            subtitle: Text('${p.fileCount} files'),
            children: [
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  onPressed: () async {
                    final n = await askText(context, 'Rename app', initial: names[i]);
                    if (n != null && n.isNotEmpty) setState(() => names[i] = cleanName(n));
                  },
                  icon: const Icon(Icons.edit, size: 18),
                  label: const Text('Rename'),
                ),
              ),
              SizedBox(
                height: 220,
                child: ListView(children: [
                  for (final f in files) ListTile(dense: true, title: Text(f)),
                ]),
              ),
            ],
          );
        },
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: FilledButton(
            onPressed: count == 0 ? null : () => Navigator.pop(context, _result()),
            child: Text('Import $count app(s)'),
          ),
        ),
      ),
    );
  }
}

class ProjectScreen extends StatefulWidget {
  final String name;
  const ProjectScreen({super.key, required this.name});
  @override
  State<ProjectScreen> createState() => _ProjectScreenState();
}

class _ProjectScreenState extends State<ProjectScreen> {
  Project? p;
  bool busy = false;
  bool hasBackup = false;
  String fileFilter = '';
  /// Files created or replaced; saved on disk with the project.
  Set<String> get newFiles => p?.changed ?? <String>{};
  BuildFailure? lastFail;
  Diagnosis? diag;
  final logs = <String>[];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final pr = await Store.load(widget.name);
    final hb = await Store.hasBackup(widget.name);
    if (!mounted) return;
    setState(() {
      p = pr;
      hasBackup = hb;
    });
  }

  void _log(String s) {
    if (mounted) setState(() => logs.add(s));
  }

  Future<void> _run(Future<void> Function() job) async {
    if (busy) return;
    setState(() => busy = true);
    try {
      await job();
    } catch (e) {
      _log('Error: ${e.toString().replaceFirst('Exception: ', '')}');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<GitHub> _gh() async {
    final t = await Settings.get('gh');
    if (t.isEmpty) throw Exception('Add your GitHub token in Settings first.');
    return GitHub(t, _log);
  }

  Future<void> _upload() => _run(() async {
        final proj = p!;
        final gh = await _gh();
        if (ensureWorkflow(proj)) {
          await Store.save(proj);
          _log('Added .github/workflows/build-apk.yml');
        }
        _log(await gh.upload(proj, 'Update from Project Vault'));
        proj.removed.clear();
        await Store.save(proj, backup: false);
        await _load();
      });

  Future<void> _build() async {
    diag = null;
    await _run(() async {
      final proj = p!;
      final gh = await _gh();
      List<int> apk;
      try {
        apk = await gh.buildApk(proj);
      } on BuildFailure catch (f) {
        lastFail = f;
        _log('Build failed at step "${f.step}".');
        _log(f.url);
        await _diagnose();
        return;
      }
      final dir = await getExternalStorageDirectory() ?? await getApplicationDocumentsDirectory();
      final f = File('${dir.path}/${proj.name}.apk');
      await f.writeAsBytes(apk);
      _log('Build finished. Choose a folder to save the APK...');
      final saved = await FilePicker.platform.saveFile(
        dialogTitle: 'Save the APK to a folder',
        fileName: '${proj.name}.apk',
        bytes: Uint8List.fromList(apk),
      );
      _log(saved == null ? 'APK was not saved to a folder.' : 'APK saved to your folder.');
      await OpenFilex.open(f.path);
    });
    if (diag != null && mounted) _openDiagnosis();
  }

  /// Asks the AI why the last build failed. Call inside _run.
  Future<void> _diagnose() async {
    final f = lastFail;
    if (f == null) return;
    final key = await Settings.get('ai');
    if (key.isEmpty) {
      _log('No API key saved. Tap "Copy error" and paste it into any AI chat.');
      diag = Diagnosis(
          'No Anthropic API key is saved, so the app cannot find the reason itself. '
          'Tap "Copy error" below, paste it into any AI chat, then bring the AI\'s files back with "Paste AI files".',
          []);
      return;
    }
    final model = await Settings.get('model', defaultModel);
    _log('Finding the exact reason…');
    diag = await diagnoseBuild(key, model, p!, f.step, f.log);
    _log('Reason found. ${diag!.fixes.length} file(s) to change.');
  }

  /// Text to paste into any AI chat.
  String _errorText(bool withFiles) {
    final f = lastFail;
    final proj = p!;
    final b = StringBuffer()
      ..writeln('My Flutter app failed to build on GitHub Actions. Find the exact reason. '
          'Reply with a short reason, then ONLY the files that must change, each as a line '
          '"=== FILE: path ===" followed by the COMPLETE new contents of that file '
          '(no snippets, no "..." placeholders, no code fences).')
      ..writeln()
      ..writeln('Failed step: ${f?.step ?? 'unknown'}')
      ..writeln()
      ..writeln('Build log:')
      ..writeln(f?.log ?? '(no log)')
      ..writeln()
      ..writeln('Project files:')
      ..writeln(([...proj.text.keys, ...proj.bin.keys]..sort()).join('\n'));
    if (withFiles) {
      b
        ..writeln()
        ..writeln('Project contents:')
        ..write(makeBundle(proj.text));
    }
    return b.toString();
  }

  Future<void> _copyError(bool withFiles) async {
    await Clipboard.setData(ClipboardData(text: _errorText(withFiles)));
    _log(withFiles ? 'Copied the error and all files.' : 'Copied the error.');
  }

  /// Applies an AI reply that uses "=== FILE: path ===" blocks.
  Future<void> _pasteAi() async {
    final t = await askPaste(context, 'the AI reply (files)');
    if (t == null) return;
    await _run(() async {
      final files = parseBundle(t);
      if (files.isEmpty) {
        throw Exception('No "=== FILE: path ===" blocks found in the pasted text.');
      }
      final proj = p!;
      final names = <String>[];
      files.forEach((k, v) {
        var s = v.trimRight();
        if (s.startsWith('```')) s = s.substring(s.indexOf('\n') + 1);
        if (s.endsWith('```')) s = s.substring(0, s.lastIndexOf('```')).trimRight();
        proj.text[k] = '$s\n';
        proj.bin.remove(k);
        proj.removed.remove(k);
        proj.changed.add(k);
        names.add(k);
      });
      await Store.save(proj);
      _log('Updated ${names.length} file(s):');
      for (final n in names) {
        _log('  ~ $n');
      }
      _log('Tap "Upload to GitHub", then "Build APK".');
      await _load();
    });
  }

  Future<void> _why() async {
    diag = null;
    await _run(_diagnose);
    if (diag != null && mounted) _openDiagnosis();
  }

  void _openDiagnosis() {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => DiagnosisScreen(
          project: p!,
          diag: diag!,
          log: lastFail?.log ?? '',
          url: lastFail?.url ?? '',
          onReplace: _replaceFile,
          onApply: _applyFix,
          errorText: _errorText,
        ),
      ),
    );
  }

  /// Applies an AI fix directly. Returns an error message, or null on success.
  Future<String?> _applyFix(Fix f) async {
    final proj = p!;
    final res = f.apply(proj.text[f.path]);
    if (res == null) {
      return 'The text to replace was not found in ${f.path}. Use Add file or Paste instead.';
    }
    proj.text[f.path] = res;
    proj.removed.remove(f.path);
    proj.changed.add(f.path);
    await Store.save(proj);
    _log('AI fix applied to ${f.path}. Upload to GitHub, then Build APK.');
    if (mounted) setState(() {});
    return null;
  }

  /// Replaces one file by adding a file from the phone or by pasting its text.
  /// Returns true if the file was changed.
  Future<bool> _replaceFile(String path) async {
    final choice = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          ListTile(title: Text(path, style: const TextStyle(fontWeight: FontWeight.bold))),
          ListTile(
            leading: const Icon(Icons.attach_file),
            title: const Text('Add file'),
            subtitle: const Text('Pick the downloaded file from your phone'),
            onTap: () => Navigator.pop(ctx, 'add'),
          ),
          ListTile(
            leading: const Icon(Icons.content_paste),
            title: const Text('Paste file'),
            subtitle: const Text('Paste the full file text'),
            onTap: () => Navigator.pop(ctx, 'paste'),
          ),
        ]),
      ),
    );
    if (choice == null || !mounted) return false;
    List<int> bytes;
    if (choice == 'add') {
      final r = await FilePicker.platform.pickFiles(withData: true);
      final b = (r == null || r.files.isEmpty) ? null : r.files.first.bytes;
      if (b == null) return false;
      bytes = b;
    } else {
      final t = await askPaste(context, path);
      if (t == null) return false;
      bytes = utf8.encode(t);
    }
    final proj = p!;
    proj.text.remove(path);
    proj.bin.remove(path);
    try {
      final s = utf8.decode(bytes);
      if (s.contains('\u0000')) throw const FormatException('binary');
      proj.text[path] = s;
    } catch (_) {
      proj.bin[path] = bytes;
    }
    proj.removed.remove(path);
    proj.changed.add(path);
    await Store.save(proj);
    _log('Replaced $path. Upload to GitHub, then Build APK.');
    await _load();
    return true;
  }

  Future<void> _ai() async {
    final req = await askText(context, 'What should the AI change?',
        lines: 4, hint: 'e.g. add a dark mode switch');
    if (req == null || req.isEmpty) return;
    await _run(() async {
      final proj = p!;
      final key = await Settings.get('ai');
      if (key.isEmpty) throw Exception('Add your Anthropic API key in Settings first.');
      final model = await Settings.get('model', defaultModel);
      _log('Asking AI… this can take a minute.');
      final changed = await Ai.update(key, model, proj, req);
      final added = <String>[], edited = <String>[];
      changed.forEach((k, v) {
        if (!proj.text.containsKey(k)) {
          added.add(k);
        } else if (proj.text[k] != v) {
          edited.add(k);
        }
        proj.text[k] = v;
        if (added.contains(k) || edited.contains(k)) proj.changed.add(k);
      });
      await Store.save(proj);
      _log('AI updated ${edited.length} file(s), added ${added.length}.');
      for (final k in edited) {
        _log('  ~ $k');
      }
      for (final k in added) {
        _log('  + $k');
      }
      _log('Tap "Upload to GitHub" to push the changes.');
      await _load();
    });
  }

  Future<void> _undo() => _run(() async {
        await Store.restoreBackup(widget.name);
        await _load();
        _log('Restored the previous version.');
      });

  Future<void> _export(bool zip) => _run(() async {
        final proj = p!;
        final data = zip
            ? Uint8List.fromList(exportZip(proj))
            : Uint8List.fromList(utf8.encode(makeBundle(proj.text)));
        final saved = await FilePicker.platform.saveFile(
          dialogTitle: 'Choose a folder to save to',
          fileName: '${proj.name}${zip ? '.zip' : '.bundle.txt'}',
          bytes: data,
        );
        _log(saved == null ? 'Save cancelled.' : 'Saved to your folder.');
      });

  Future<void> _removeFile(String path) async {
    final proj = p!;
    final t = proj.text.remove(path);
    final bn = proj.bin.remove(path);
    final wasChanged = proj.changed.remove(path);
    proj.removed.add(path);
    await Store.save(proj);
    if (!mounted) return;
    setState(() {});
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text('Removed $path'),
      action: SnackBarAction(
        label: 'Undo',
        onPressed: () async {
          if (t != null) proj.text[path] = t;
          if (bn != null) proj.bin[path] = bn;
          proj.removed.remove(path);
          if (wasChanged) proj.changed.add(path);
          await Store.save(proj);
          if (mounted) setState(() {});
        },
      ),
    ));
  }

  Future<void> _removeShown(List<String> paths) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Remove ${paths.length} file(s)?'),
        content: const Text('They are removed from this app and deleted from GitHub on the next upload.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Remove')),
        ],
      ),
    );
    if (ok != true) return;
    final proj = p!;
    for (final f in paths) {
      proj.text.remove(f);
      proj.bin.remove(f);
      proj.removed.add(f);
      proj.changed.remove(f);
    }
    await Store.save(proj);
    _log('Removed ${paths.length} file(s). Upload to GitHub to delete them there too.');
    await _load();
  }

  /// Creates a new file from a typed path + pasted content (like GitHub's "Create new file").
  Future<void> _newFile() async {
    final proj = p!;
    final r = await askNewFile(context,
        exists: (path) => proj.text.containsKey(path) || proj.bin.containsKey(path));
    if (r == null || !mounted) return;
    final (path, content) = r;
    await _run(() async {
      final existed = proj.text.containsKey(path) || proj.bin.containsKey(path);
      proj.bin.remove(path);
      proj.text[path] = content.isEmpty || content.endsWith('\n') ? content : '$content\n';
      proj.removed.remove(path);
      proj.changed.add(path);
      await Store.save(proj);
      _log('${existed ? 'Overwrote' : 'Created'} $path. Upload to GitHub, then Build APK.');
      await _load();
    });
  }

  Map<String, String> _newText() {
    final proj = p!;
    return {
      for (final k in newFiles.toList()..sort())
        if (proj.text.containsKey(k)) k: proj.text[k]!
    };
  }

  Future<void> _exportNew() => _run(() async {
        final files = _newText();
        if (files.isEmpty) throw Exception('No new text files to bundle.');
        final data = Uint8List.fromList(utf8.encode(makeBundle(files)));
        final saved = await FilePicker.platform.saveFile(
          dialogTitle: 'Choose a folder to save to',
          fileName: '${p!.name}-changed.bundle.txt',
          bytes: data,
        );
        _log(saved == null ? 'Save cancelled.' : 'Saved bundle of ${files.length} new file(s).');
      });

  Future<void> _copyNew() async {
    final files = _newText();
    if (files.isEmpty) return;
    await Clipboard.setData(ClipboardData(text: makeBundle(files)));
    _log('Copied bundle of ${files.length} new file(s).');
  }

  Future<void> _addFiles() async {
    final r = await FilePicker.platform.pickFiles(allowMultiple: true, withData: true);
    if (r == null || r.files.isEmpty || !mounted) return;
    final folder = await askText(context, 'Folder inside the app',
        hint: 'Leave empty for top level, e.g. app/src/main/java');
    if (folder == null) return;
    await _run(() async {
      final proj = p!;
      final prefix = folder.replaceAll('\\', '/').split('/').where((s) => s.isNotEmpty).join('/');
      var n = 0;
      for (final f in r.files) {
        final bytes = f.bytes;
        if (bytes == null) continue;
        final path = prefix.isEmpty ? f.name : '$prefix/${f.name}';
        proj.text.remove(path);
        proj.bin.remove(path);
        try {
          final s = utf8.decode(bytes);
          if (s.contains('\u0000')) throw const FormatException('binary');
          proj.text[path] = s;
        } catch (_) {
          proj.bin[path] = bytes;
        }
        proj.removed.remove(path);
        proj.changed.add(path);
        n++;
      }
      await Store.save(proj);
      _log('Added or updated $n file(s).');
      await _load();
    });
  }

  Future<void> _delete() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Delete "${widget.name}"?'),
        content: const Text('This removes the local copy only, not your GitHub repo.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Delete')),
        ],
      ),
    );
    if (ok == true) {
      await Store.delete(widget.name);
      if (mounted) Navigator.pop(context);
    }
  }

  @override
  Widget build(BuildContext context) {
    final proj = p;
    final files = proj == null ? <String>[] : ([...proj.text.keys, ...proj.bin.keys]..sort());
    final shown = fileFilter.isEmpty
        ? files
        : files.where((f) => f.toLowerCase().contains(fileFilter)).toList();
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.name),
        actions: [
          PopupMenuButton<String>(
            icon: const Icon(Icons.add),
            tooltip: 'Add',
            enabled: !busy && proj != null,
            onSelected: (v) => v == 'new' ? _newFile() : _addFiles(),
            itemBuilder: (_) => const [
              PopupMenuItem(
                  value: 'new',
                  child: ListTile(
                      leading: Icon(Icons.note_add),
                      title: Text('Create new file'),
                      subtitle: Text('Type a path, paste the code'),
                      contentPadding: EdgeInsets.zero)),
              PopupMenuItem(
                  value: 'pick',
                  child: ListTile(
                      leading: Icon(Icons.attach_file),
                      title: Text('Add files from phone'),
                      contentPadding: EdgeInsets.zero)),
            ],
          ),
          IconButton(icon: const Icon(Icons.delete_outline), onPressed: _delete),
        ],
      ),
      body: proj == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Wrap(spacing: 8, runSpacing: 8, children: [
                  FilledButton.icon(
                      onPressed: busy ? null : _upload,
                      icon: const Icon(Icons.cloud_upload),
                      label: const Text('Upload to GitHub')),
                  FilledButton.icon(
                      onPressed: busy ? null : _build,
                      icon: const Icon(Icons.android),
                      label: const Text('Build APK')),
                  FilledButton.tonalIcon(
                      onPressed: busy ? null : _ai,
                      icon: const Icon(Icons.auto_awesome),
                      label: const Text('AI update')),
                  if (lastFail != null)
                    FilledButton.tonalIcon(
                        onPressed: () => _copyError(false),
                        icon: const Icon(Icons.copy),
                        label: const Text('Copy error')),
                  if (lastFail != null)
                    FilledButton.tonalIcon(
                        onPressed: () => _copyError(true),
                        icon: const Icon(Icons.copy_all),
                        label: const Text('Copy error + files')),
                  OutlinedButton.icon(
                      onPressed: busy ? null : _pasteAi,
                      icon: const Icon(Icons.content_paste_go),
                      label: const Text('Paste AI files')),
                  if (lastFail != null)
                    FilledButton.tonalIcon(
                        onPressed: busy ? null : _why,
                        icon: const Icon(Icons.bug_report_outlined),
                        label: const Text('Why did it fail?')),
                  if (hasBackup)
                    OutlinedButton.icon(
                        onPressed: busy ? null : _undo,
                        icon: const Icon(Icons.undo),
                        label: const Text('Undo last change')),
                  FilledButton.tonalIcon(
                      onPressed: busy ? null : _newFile,
                      icon: const Icon(Icons.note_add),
                      label: const Text('New file')),
                  OutlinedButton.icon(
                      onPressed: busy ? null : _addFiles,
                      icon: const Icon(Icons.note_add_outlined),
                      label: const Text('Add files')),
                  OutlinedButton.icon(
                      onPressed: busy ? null : () => _export(true),
                      icon: const Icon(Icons.download),
                      label: const Text('Download zip')),
                  OutlinedButton.icon(
                      onPressed: busy ? null : () => _export(false),
                      icon: const Icon(Icons.description_outlined),
                      label: const Text('Download bundle')),
                ]),
                const SizedBox(height: 16),
                if (newFiles.any((f) => proj.text.containsKey(f) || proj.bin.containsKey(f)))
                  Card(
                    margin: const EdgeInsets.only(bottom: 12),
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Row(children: [
                          Expanded(
                            child: Text(
                              'Changed files (${newFiles.where((f) => proj.text.containsKey(f) || proj.bin.containsKey(f)).length})',
                              style: const TextStyle(fontWeight: FontWeight.bold),
                            ),
                          ),
                          TextButton(
                              onPressed: () async {
                                proj.changed.clear();
                                await Store.save(proj, backup: false);
                                if (mounted) setState(() {});
                              },
                              child: const Text('Clear')),
                        ]),
                        for (final f in (newFiles
                                .where((f) => proj.text.containsKey(f) || proj.bin.containsKey(f))
                                .toList()
                              ..sort()))
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: 2),
                            child: Text(f,
                                style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
                          ),
                        const SizedBox(height: 8),
                        Wrap(spacing: 8, runSpacing: 8, children: [
                          FilledButton.icon(
                              onPressed: busy ? null : _exportNew,
                              icon: const Icon(Icons.description_outlined),
                              label: const Text('Bundle (changed only)')),
                          OutlinedButton.icon(
                              onPressed: busy ? null : _copyNew,
                              icon: const Icon(Icons.copy),
                              label: const Text('Copy bundle')),
                        ]),
                      ]),
                    ),
                  ),
                ExpansionTile(
                  title: Text('${proj.fileCount} files'),
                  subtitle: proj.removed.isEmpty
                      ? null
                      : Text('${proj.removed.length} removed, deleted from GitHub on next upload'),
                  children: [
                    TextField(
                      onChanged: (v) => setState(() => fileFilter = v.trim().toLowerCase()),
                      decoration: const InputDecoration(
                          hintText: 'Search file name (tap a file to add or paste)',
                          prefixIcon: Icon(Icons.search),
                          isDense: true),
                    ),
                    if (shown.isNotEmpty && fileFilter.isNotEmpty)
                      Align(
                        alignment: Alignment.centerLeft,
                        child: TextButton.icon(
                          onPressed: busy ? null : () => _removeShown(shown),
                          icon: const Icon(Icons.delete_sweep_outlined),
                          label: Text('Remove all ${shown.length} shown'),
                        ),
                      ),
                    SizedBox(
                      height: 300,
                      child: ListView(
                        children: [
                          for (final f in shown)
                            ListTile(
                              dense: true,
                              title: Text(f),
                              onTap: busy ? null : () => _replaceFile(f),
                              trailing: IconButton(
                                icon: const Icon(Icons.delete_outline),
                                onPressed: busy ? null : () => _removeFile(f),
                              ),
                            ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                if (busy) const LinearProgressIndicator(),
                Container(
                  height: 260,
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: SingleChildScrollView(
                    reverse: true,
                    child: SelectableText(
                      logs.isEmpty ? 'Activity log' : logs.join('\n'),
                      style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                    ),
                  ),
                ),
              ],
            ),
    );
  }
}

class DiagnosisScreen extends StatefulWidget {
  final Project project;
  final Diagnosis diag;
  final String log, url;
  final Future<bool> Function(String path) onReplace;
  final Future<String?> Function(Fix fix) onApply;
  final String Function(bool withFiles) errorText;
  const DiagnosisScreen({
    super.key,
    required this.project,
    required this.diag,
    required this.log,
    required this.url,
    required this.onReplace,
    required this.onApply,
    required this.errorText,
  });
  @override
  State<DiagnosisScreen> createState() => _DiagnosisScreenState();
}

class _DiagnosisScreenState extends State<DiagnosisScreen> {
  String filter = '';
  final done = <String>{};

  void _msg(String s) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(s)));

  Widget _code(String t) => Container(
        width: double.infinity,
        margin: const EdgeInsets.only(top: 4, bottom: 8),
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(8),
        ),
        child: SelectableText(t, style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
      );

  Widget _card(Fix f) {
    final isDone = done.contains(f.path);
    final exists = widget.project.text.containsKey(f.path) || widget.project.bin.containsKey(f.path);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(
                child: Text(f.path, style: const TextStyle(fontWeight: FontWeight.bold))),
            if (isDone) const Icon(Icons.check_circle, color: Colors.green),
          ]),
          const SizedBox(height: 4),
          Wrap(spacing: 6, children: [
            Chip(
                visualDensity: VisualDensity.compact,
                label: Text(f.whole ? 'Change the whole file' : 'Change part of the file')),
            if (!exists)
              const Chip(visualDensity: VisualDensity.compact, label: Text('New file')),
          ]),
          if (f.note.isNotEmpty) Text(f.note),
          if (!f.whole) ...[
            const SizedBox(height: 8),
            const Text('Find this text:'),
            _code(f.find),
            const Text('Replace it with:'),
            _code(f.replace),
          ],
          Wrap(spacing: 8, children: [
            TextButton.icon(
              icon: const Icon(Icons.copy),
              label: Text(f.whole ? 'Copy whole file' : 'Copy new text'),
              onPressed: () async {
                await Clipboard.setData(ClipboardData(text: f.whole ? f.content : f.replace));
                _msg('Copied');
              },
            ),
            TextButton.icon(
              icon: const Icon(Icons.auto_fix_high),
              label: const Text('Apply AI fix'),
              onPressed: () async {
                final err = await widget.onApply(f);
                if (!mounted) return;
                if (err == null) setState(() => done.add(f.path));
                _msg(err ?? 'Applied to ${f.path}');
              },
            ),
            TextButton.icon(
              icon: const Icon(Icons.attach_file),
              label: const Text('Add / paste file'),
              onPressed: () async {
                if (await widget.onReplace(f.path) && mounted) {
                  setState(() => done.add(f.path));
                }
              },
            ),
          ]),
        ]),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final fixes = widget.diag.fixes
        .where((f) => filter.isEmpty || f.path.toLowerCase().contains(filter))
        .toList();
    return Scaffold(
      appBar: AppBar(title: const Text('Why the build failed')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Card(
          color: Theme.of(context).colorScheme.errorContainer,
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: SelectableText(widget.diag.reason.isEmpty
                ? 'The AI gave no reason. See the build log below.'
                : widget.diag.reason),
          ),
        ),
        const SizedBox(height: 12),
        Wrap(spacing: 8, runSpacing: 8, children: [
          FilledButton.icon(
            icon: const Icon(Icons.copy),
            label: const Text('Copy error'),
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: widget.errorText(false)));
              _msg('Copied. Paste it into your AI chat.');
            },
          ),
          FilledButton.tonalIcon(
            icon: const Icon(Icons.copy_all),
            label: const Text('Copy error + files'),
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: widget.errorText(true)));
              _msg('Copied error and all files.');
            },
          ),
        ]),
        const SizedBox(height: 12),
        if (widget.diag.fixes.isEmpty)
          const Text('No file suggestions. Use the copy buttons above, then "Paste AI files" on the project screen.')
        else ...[
          Text('${widget.diag.fixes.length} file(s) to change',
              style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          TextField(
            onChanged: (v) => setState(() => filter = v.trim().toLowerCase()),
            decoration: const InputDecoration(
                hintText: 'Search file name', prefixIcon: Icon(Icons.search), isDense: true),
          ),
          const SizedBox(height: 8),
          for (final f in fixes) _card(f),
          if (fixes.isEmpty) const Text('No file matches your search.'),
        ],
        const SizedBox(height: 8),
        ExpansionTile(
          title: const Text('Build log'),
          children: [
            if (widget.url.isNotEmpty) SelectableText(widget.url),
            _code(widget.log.isEmpty ? '(empty)' : widget.log),
          ],
        ),
        const SizedBox(height: 8),
        const Text('After changing files: go back, tap Upload to GitHub, then Build APK.'),
      ]),
    );
  }
}

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});
  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final gh = TextEditingController();
  final ai = TextEditingController();
  final model = TextEditingController();

  @override
  void initState() {
    super.initState();
    () async {
      gh.text = await Settings.get('gh');
      ai.text = await Settings.get('ai');
      model.text = await Settings.get('model', defaultModel);
      if (mounted) setState(() {});
    }();
  }

  Future<void> _save() async {
    await Settings.set('gh', gh.text.trim());
    await Settings.set('ai', ai.text.trim());
    await Settings.set('model', model.text.trim().isEmpty ? defaultModel : model.text.trim());
    if (mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Settings')),
        body: ListView(padding: const EdgeInsets.all(16), children: [
          TextField(
            controller: gh,
            obscureText: true,
            decoration: const InputDecoration(
              labelText: 'GitHub token',
              helperText: 'Classic token with "repo", "workflow" and "delete_repo" scopes',
            ),
          ),
          const SizedBox(height: 16),
          TextField(
            controller: ai,
            obscureText: true,
            decoration: const InputDecoration(labelText: 'Anthropic API key'),
          ),
          const SizedBox(height: 16),
          TextField(controller: model, decoration: const InputDecoration(labelText: 'AI model')),
          const SizedBox(height: 24),
          FilledButton(onPressed: _save, child: const Text('Save')),
        ]),
      );
}
