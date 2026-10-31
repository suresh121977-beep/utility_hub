import 'dart:convert';
import 'package:archive/archive.dart';

class Project {
  final String name;
  final Map<String, String> text;
  final Map<String, List<int>> bin;
  /// Files removed locally; deleted from GitHub on the next upload.
  final Set<String> removed;
  /// Files created or replaced (by paste, AI, fix or add). Kept on disk so the
  /// list survives closing the app; used for the "changed files" bundle.
  final Set<String> changed;
  Project(this.name, this.text, this.bin, {Set<String>? removed, Set<String>? changed})
      : removed = removed ?? <String>{},
        changed = changed ?? <String>{};
  int get fileCount => text.length + bin.length;
  String get repoName => name.trim().replaceAll(RegExp(r'\s+'), '-');
}

String cleanName(String s) {
  final c = s.replaceAll(RegExp(r'[^A-Za-z0-9 _.\-]'), '').trim();
  return c.isEmpty ? 'app' : c;
}

const _skip = ['.git/', 'build/', '.dart_tool/', 'node_modules/', '.gradle/', '.idea/'];
final _hdr = RegExp(r'^=== FILE: (.+?) ===$', multiLine: true);

String makeBundle(Map<String, String> files) {
  final keys = files.keys.toList()..sort();
  final b = StringBuffer();
  for (final k in keys) {
    final c = files[k]!;
    b.write('=== FILE: $k ===\n');
    b.write(c);
    if (c.isNotEmpty && !c.endsWith('\n')) b.write('\n');
  }
  return b.toString();
}

Map<String, String> parseBundle(String s) {
  final out = <String, String>{};
  final ms = _hdr.allMatches(s).toList();
  for (var i = 0; i < ms.length; i++) {
    var start = ms[i].end + 1;
    final end = i + 1 < ms.length ? ms[i + 1].start : s.length;
    if (start > end) start = end;
    out[ms[i].group(1)!.trim()] = s.substring(start, end);
  }
  return out;
}

Project projectFromZip(String name, List<int> zipBytes) {
  final arc = ZipDecoder().decodeBytes(zipBytes);
  final files = arc.files.where((f) => f.isFile).toList();
  final names = files.map((f) => f.name.replaceAll('\\', '/')).toList();
  var prefix = '';
  if (names.isNotEmpty && names.every((n) => n.contains('/'))) {
    final first = names.first.split('/').first;
    if (names.every((n) => n.startsWith('$first/'))) prefix = '$first/';
  }
  final text = <String, String>{};
  final bin = <String, List<int>>{};
  for (var i = 0; i < files.length; i++) {
    final path = names[i].substring(prefix.length);
    if (path.isEmpty || _skip.any((d) => '/$path'.contains('/$d'))) continue;
    final data = files[i].content as List<int>;
    try {
      final s = utf8.decode(data);
      if (s.contains('\u0000')) throw const FormatException('binary');
      text[path] = s;
    } catch (_) {
      bin[path] = data;
    }
  }
  return Project(name, text, bin);
}

List<int> exportZip(Project p) {
  final a = Archive();
  p.text.forEach((k, v) {
    final b = utf8.encode(v);
    a.addFile(ArchiveFile(k, b.length, b));
  });
  p.bin.forEach((k, v) => a.addFile(ArchiveFile(k, v.length, v)));
  return ZipEncoder().encode(a)!;
}

const _wfPath = '.github/workflows/build-apk.yml';

const _flutterWf = r'''name: Build APK
on:
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: subosito/flutter-action@v2
        with:
          channel: stable
          cache: true
      - name: Create Android scaffold if missing
        run: |
          if [ ! -d android ]; then flutter create --platforms=android .; fi
          M=android/app/src/main/AndroidManifest.xml
          grep -q android.permission.INTERNET $M || sed -i 's#<application#<uses-permission android:name="android.permission.INTERNET"/>\n    <application#' $M
      - run: flutter pub get
      - name: Patch plugins to compileSdk 36
        run: |
          find "${PUB_CACHE:-$HOME/.pub-cache}" -path '*/android/build.gradle' -exec sed -i -E 's/compileSdk(Version)?[ =]+[0-9]+/compileSdk 36/' {} +
      - run: flutter build apk --release
      - uses: actions/upload-artifact@v4
        with:
          name: app-apk
          path: build/app/outputs/flutter-apk/app-release.apk
''';

const _gradleWf = r'''name: Build APK
on:
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
      - run: chmod +x gradlew && ./gradlew assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: app-apk
          path: '**/build/outputs/apk/**/*.apk'
''';

/// Adds build-apk.yml if the project has none. Returns true if added.
bool ensureWorkflow(Project p) {
  if (p.text.keys.any((k) => k.startsWith('.github/workflows/'))) return false;
  if (p.text.containsKey('pubspec.yaml')) {
    p.text[_wfPath] = _flutterWf;
    return true;
  }
  if (p.text.containsKey('gradlew') || p.bin.containsKey('gradlew')) {
    p.text[_wfPath] = _gradleWf;
    return true;
  }
  return false;
}

final _appHdr = RegExp(r'^=== APP: (.+?) ===$', multiLine: true);

/// Several apps in ONE text file: "=== APP: name ===" then its files.
String makeVaultBundle(List<Project> ps) {
  final b = StringBuffer();
  for (final p in ps) {
    b.write('=== APP: ${p.name} ===\n');
    b.write(makeBundle(p.text));
  }
  return b.toString();
}

List<Project> parseVaultBundle(String s) {
  final ms = _appHdr.allMatches(s).toList();
  final out = <Project>[];
  for (var i = 0; i < ms.length; i++) {
    var start = ms[i].end + 1;
    final end = i + 1 < ms.length ? ms[i + 1].start : s.length;
    if (start > end) start = end;
    out.add(Project(cleanName(ms[i].group(1)!), parseBundle(s.substring(start, end)), {}));
  }
  return out;
}

/// Understands: one project zip, one app bundle (.txt), a multi-app bundle (.txt),
/// a vault backup zip, or a zip that contains several project zips.
List<Project> projectsFromAny(String fileName, List<int> bytes) {
  final base = cleanName(fileName
      .replaceAll(RegExp(r'\.(zip|txt)$', caseSensitive: false), '')
      .replaceAll(RegExp(r'\.bundle$'), ''));
  if (!fileName.toLowerCase().endsWith('.zip')) {
    final s = utf8.decode(bytes);
    if (_appHdr.hasMatch(s)) return parseVaultBundle(s);
    final files = parseBundle(s);
    return files.isEmpty ? [] : [Project(base, files, {})];
  }
  final arc = ZipDecoder().decodeBytes(bytes);
  final files = arc.files.where((f) => f.isFile).toList();
  if (files.isNotEmpty && files.every((f) => f.name.toLowerCase().endsWith('.zip'))) {
    return [
      for (final f in files)
        ...projectsFromAny(f.name.replaceAll('\\', '/').split('/').last, f.content as List<int>)
    ];
  }
  final bundles = <String, String>{};
  final bins = <String, Map<String, List<int>>>{};
  for (final f in files) {
    final parts = f.name.replaceAll('\\', '/').split('/');
    if (parts.length < 2) continue;
    final name = parts.first;
    final rest = parts.sublist(1).join('/');
    final data = f.content as List<int>;
    if (rest == '$name.bundle.txt') {
      bundles[name] = utf8.decode(data);
    } else if (rest.startsWith('bin/')) {
      (bins[name] ??= {})[rest.substring(4)] = data;
    }
  }
  if (bundles.isNotEmpty) {
    return [
      for (final e in bundles.entries)
        Project(cleanName(e.key), parseBundle(e.value), bins[e.key] ?? {})
    ];
  }
  final p = projectFromZip(base, bytes);
  return p.fileCount == 0 ? [] : [p];
}
