import 'dart:convert';
import 'dart:io';
import 'package:archive/archive.dart';
import 'package:path_provider/path_provider.dart';
import 'bundle.dart';

class Settings {
  static Future<File> _file() async {
    final d = await getApplicationDocumentsDirectory();
    return File('${d.path}/settings.json');
  }

  static Future<Map<String, dynamic>> _read() async {
    try {
      final f = await _file();
      if (await f.exists()) {
        return jsonDecode(await f.readAsString()) as Map<String, dynamic>;
      }
    } catch (_) {}
    return {};
  }

  static Future<String> get(String k, [String d = '']) async =>
      ((await _read())[k] as String?) ?? d;

  static Future<void> set(String k, String v) async {
    final m = await _read();
    m[k] = v;
    await (await _file()).writeAsString(jsonEncode(m));
  }
}

class Store {
  static Future<Directory> _root() async {
    final d = await getApplicationDocumentsDirectory();
    final r = Directory('${d.path}/projects');
    if (!await r.exists()) await r.create(recursive: true);
    return r;
  }

  static Future<List<String>> list() async {
    final r = await _root();
    final names = <String>[];
    await for (final e in r.list()) {
      if (e is Directory) {
        names.add(e.uri.pathSegments.where((s) => s.isNotEmpty).last);
      }
    }
    names.sort((a, b) => a.toLowerCase().compareTo(b.toLowerCase()));
    return names;
  }

  static Future<Project> load(String name) async {
    final dir = Directory('${(await _root()).path}/$name');
    final bf = File('${dir.path}/$name.bundle.txt');
    final text = await bf.exists() ? parseBundle(await bf.readAsString()) : <String, String>{};
    final bin = <String, List<int>>{};
    final bd = Directory('${dir.path}/bin');
    if (await bd.exists()) {
      await for (final e in bd.list(recursive: true)) {
        if (e is File) {
          final rel = e.path.substring(bd.path.length + 1).replaceAll('\\', '/');
          bin[rel] = await e.readAsBytes();
        }
      }
    }
    final removed = <String>{};
    final rf = File('${dir.path}/removed.json');
    if (await rf.exists()) {
      try {
        removed.addAll((jsonDecode(await rf.readAsString()) as List).cast<String>());
      } catch (_) {}
    }
    final changed = <String>{};
    final cf = File('${dir.path}/changed.json');
    if (await cf.exists()) {
      try {
        changed.addAll((jsonDecode(await cf.readAsString()) as List).cast<String>());
      } catch (_) {}
    }
    return Project(name, text, bin, removed: removed, changed: changed);
  }

  static Future<void> save(Project p, {bool backup = true}) async {
    final dir = Directory('${(await _root()).path}/${p.name}');
    await dir.create(recursive: true);
    final bf = File('${dir.path}/${p.name}.bundle.txt');
    if (backup && await bf.exists()) await bf.copy('${dir.path}/previous.bak');
    await bf.writeAsString(makeBundle(p.text));
    await File('${dir.path}/removed.json').writeAsString(jsonEncode(p.removed.toList()));
    await File('${dir.path}/changed.json').writeAsString(jsonEncode(p.changed.toList()));
    final bd = Directory('${dir.path}/bin');
    if (await bd.exists()) await bd.delete(recursive: true);
    for (final e in p.bin.entries) {
      final f = File('${bd.path}/${e.key}');
      await f.parent.create(recursive: true);
      await f.writeAsBytes(e.value);
    }
  }

  static Future<bool> hasBackup(String name) async =>
      File('${(await _root()).path}/$name/previous.bak').exists();

  static Future<void> restoreBackup(String name) async {
    final dir = '${(await _root()).path}/$name';
    await File('$dir/previous.bak').copy('$dir/$name.bundle.txt');
  }

  static Future<bool> exists(String name) async =>
      Directory('${(await _root()).path}/$name').exists();

  /// Renames a saved app (folder and bundle file).
  static Future<void> rename(String oldName, String newName) async {
    final root = (await _root()).path;
    final d = Directory('$root/$oldName');
    if (!await d.exists()) return;
    final nd = await d.rename('$root/$newName');
    final bf = File('${nd.path}/$oldName.bundle.txt');
    if (await bf.exists()) await bf.rename('${nd.path}/$newName.bundle.txt');
  }

  static Future<void> delete(String name) async {
    final d = Directory('${(await _root()).path}/$name');
    if (await d.exists()) await d.delete(recursive: true);
  }

  /// One zip with every project (settings and tokens are NOT included).
  static Future<List<int>> backupAll({List<String>? only}) async {
    final a = Archive();
    for (final n in only ?? await list()) {
      final p = await load(n);
      final t = utf8.encode(makeBundle(p.text));
      a.addFile(ArchiveFile('$n/$n.bundle.txt', t.length, t));
      p.bin.forEach((k, v) => a.addFile(ArchiveFile('$n/bin/$k', v.length, v)));
    }
    return ZipEncoder().encode(a)!;
  }

  /// Searches app names, file names and file contents across all projects.
  static Future<List<SearchHit>> search(String query) async {
    final q = query.toLowerCase();
    final hits = <SearchHit>[];
    for (final n in await list()) {
      if (n.toLowerCase().contains(q)) hits.add(SearchHit(n, '', null));
      final p = await load(n);
      for (final e in p.text.entries) {
        if (hits.length >= 200) return hits;
        if (e.key.toLowerCase().contains(q)) {
          hits.add(SearchHit(n, e.key, null));
          continue;
        }
        for (final line in e.value.split('\n')) {
          if (line.toLowerCase().contains(q)) {
            var t = line.trim();
            if (t.length > 120) t = t.substring(0, 120);
            hits.add(SearchHit(n, e.key, t));
            break;
          }
        }
      }
      for (final k in p.bin.keys) {
        if (k.toLowerCase().contains(q)) hits.add(SearchHit(n, k, null));
      }
    }
    return hits;
  }
}

class SearchHit {
  final String project, path;
  final String? snippet;
  SearchHit(this.project, this.path, this.snippet);
}
