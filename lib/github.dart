import 'dart:convert';
import 'package:archive/archive.dart';
import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;
import 'bundle.dart';

/// Thrown when the GitHub Actions build fails. Carries the failed step and its log.
class BuildFailure implements Exception {
  final String url, step, log;
  BuildFailure(this.url, this.step, this.log);
  @override
  String toString() => 'Build failed at step "$step". Details: $url';
}

class RepoInfo {
  final String name, fullName;
  final bool private;
  RepoInfo(this.name, this.fullName, this.private);
}

class _Target {
  final String owner, repo, branch;
  _Target(this.owner, this.repo, this.branch);
}

class GitHub {
  final String token;
  final void Function(String) log;
  GitHub(this.token, this.log);

  Map<String, String> get _h => {
        'Authorization': 'Bearer $token',
        'Accept': 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        'Content-Type': 'application/json',
      };

  Future<http.Response> _req(String method, String path, [Object? body]) async {
    final uri = Uri.parse('https://api.github.com$path');
    final r = http.Request(method, uri)..headers.addAll(_h);
    if (body != null) r.body = jsonEncode(body);
    final s = await r.send().timeout(const Duration(minutes: 5));
    return http.Response.fromStream(s);
  }

  String _msg(String body) {
    try {
      return jsonDecode(body)['message'].toString();
    } catch (_) {
      return body;
    }
  }

  dynamic _ok(http.Response r) {
    if (r.statusCode >= 300) {
      throw Exception('GitHub ${r.statusCode}: ${_msg(r.body)}');
    }
    return r.body.isEmpty ? null : jsonDecode(r.body);
  }

  /// Every repository you own (public and private).
  Future<List<RepoInfo>> listRepos() async {
    final out = <RepoInfo>[];
    for (var page = 1; page <= 20; page++) {
      final list = _ok(await _req(
          'GET', '/user/repos?affiliation=owner&per_page=100&sort=updated&page=$page')) as List;
      for (final r in list) {
        out.add(RepoInfo(r['name'] as String, r['full_name'] as String, r['private'] == true));
      }
      if (list.length < 100) break;
    }
    return out;
  }

  Future<void> deleteRepo(String fullName) async {
    final r = await _req('DELETE', '/repos/$fullName');
    if (r.statusCode == 204) return;
    if (r.statusCode == 403 || r.statusCode == 404) {
      throw Exception('GitHub refused to delete $fullName. Your token needs the '
          '"delete_repo" scope: GitHub > Settings > Developer settings > Tokens (classic), '
          'tick delete_repo, then paste the token in Settings.');
    }
    throw Exception('GitHub ${r.statusCode}: ${_msg(r.body)}');
  }

  Future<void> renameRepo(String fullName, String newName) async {
    final r = await _req('PATCH', '/repos/$fullName', {'name': newName});
    if (r.statusCode >= 300) {
      throw Exception('GitHub ${r.statusCode}: ${_msg(r.body)}');
    }
  }

  String _gitSha(List<int> bytes) =>
      sha1.convert([...utf8.encode('blob ${bytes.length}'), 0, ...bytes]).toString();

  Future<String> _login() async => (_ok(await _req('GET', '/user')))['login'] as String;

  Future<_Target> _target(Project p) async {
    final user = await _login();
    final info = _ok(await _req('GET', '/repos/$user/${p.repoName}'));
    return _Target(user, p.repoName, info['default_branch'] as String);
  }

  /// Commits only files that differ from the repo. Returns a summary.
  Future<String> upload(Project p, String message) async {
    final user = await _login();
    final repo = p.repoName;
    var rr = await _req('GET', '/repos/$user/$repo');
    if (rr.statusCode == 404) {
      log('Creating public repo $repo…');
      _ok(await _req('POST', '/user/repos', {'name': repo, 'private': false, 'auto_init': true}));
      await Future.delayed(const Duration(seconds: 3));
      rr = await _req('GET', '/repos/$user/$repo');
    }
    final branch = _ok(rr)['default_branch'] as String;
    final ref = _ok(await _req('GET', '/repos/$user/$repo/git/ref/heads/$branch'));
    final commitSha = ref['object']['sha'] as String;
    final commit = _ok(await _req('GET', '/repos/$user/$repo/git/commits/$commitSha'));
    final baseTree = commit['tree']['sha'] as String;
    final tree = _ok(await _req('GET', '/repos/$user/$repo/git/trees/$baseTree?recursive=1'));
    final remote = <String, String>{
      for (final t in tree['tree'])
        if (t['type'] == 'blob') t['path'] as String: t['sha'] as String
    };

    final all = <String, List<int>>{
      for (final e in p.text.entries) e.key: utf8.encode(e.value),
      ...p.bin,
    };
    final entries = <Map<String, String?>>[];
    var n = 0;
    for (final e in all.entries) {
      n++;
      if (remote[e.key] == _gitSha(e.value)) continue;
      log('Uploading ${e.key} ($n/${all.length})');
      final blob = _ok(await _req('POST', '/repos/$user/$repo/git/blobs',
          {'content': base64Encode(e.value), 'encoding': 'base64'}));
      entries.add({
        'path': e.key,
        'mode': e.key == 'gradlew' ? '100755' : '100644',
        'type': 'blob',
        'sha': blob['sha'] as String,
      });
    }
    final dels = p.removed.where((r) => remote.containsKey(r) && !all.containsKey(r)).toList();
    for (final r in dels) {
      log('Deleting $r from GitHub');
      entries.add({'path': r, 'mode': '100644', 'type': 'blob', 'sha': null});
    }
    if (entries.isEmpty) return 'Repo $user/$repo is already up to date.';
    var use = entries;
    var skipped = false;
    var tr = await _req('POST', '/repos/$user/$repo/git/trees',
        {'base_tree': baseTree, 'tree': use});
    if (tr.statusCode == 404 && entries.any((e) => e['path']!.startsWith('.github/workflows/'))) {
      log('GitHub refused the workflow file (your token needs the "workflow" scope). Uploading the other files without it...');
      use = entries.where((e) => !e['path']!.startsWith('.github/workflows/')).toList();
      skipped = true;
      if (use.isEmpty) {
        throw Exception('Only the workflow file changed, and your token cannot write workflow files. '
            'Create a classic token with the "repo" and "workflow" scopes and save it in Settings.');
      }
      tr = await _req('POST', '/repos/$user/$repo/git/trees', {'base_tree': baseTree, 'tree': use});
    }
    final newTree = _step('create tree', tr);
    final newCommit = _step(
        'create commit',
        await _req('POST', '/repos/$user/$repo/git/commits', {
          'message': message,
          'tree': newTree['sha'],
          'parents': [commitSha],
        }));
    _step('update branch',
        await _req('PATCH', '/repos/$user/$repo/git/refs/heads/$branch', {'sha': newCommit['sha']}));
    final up = use.where((e) => e['sha'] != null).length;
    final msg = 'Uploaded $up changed file(s), deleted ${use.length - up} old file(s) in $user/$repo.';
    return skipped
        ? '$msg\nThe workflow file was NOT uploaded. Save a classic token with "repo" and "workflow" scopes in Settings, then tap Upload again.'
        : msg;
  }

  dynamic _step(String name, http.Response r) {
    if (r.statusCode >= 300) {
      throw Exception('GitHub ${r.statusCode} at step "$name": ${_msg(r.body)}');
    }
    return r.body.isEmpty ? null : jsonDecode(r.body);
  }

  Future<String> _workflowFile(String base) async {
    final r = _ok(await _req('GET', '$base/actions/workflows'));
    final paths = [for (final w in r['workflows'] as List) w['path'] as String];
    if (paths.isEmpty) throw Exception('No workflow found in the repo. Upload the project first.');
    final pick = paths.firstWhere((p) => p.endsWith('build-apk.yml'), orElse: () => paths.first);
    return pick.split('/').last;
  }

  /// Downloads the log of the failed job and keeps the useful part.
  Future<BuildFailure> _failure(String base, int runId, String url) async {
    var step = 'unknown';
    var log = '';
    try {
      final jobs = _ok(await _req('GET', '$base/actions/runs/$runId/jobs'))['jobs'] as List;
      final bad = jobs.firstWhere((j) => j['conclusion'] == 'failure', orElse: () => jobs.first);
      for (final s in bad['steps'] as List) {
        if (s['conclusion'] == 'failure') {
          step = s['name'] as String;
          break;
        }
      }
      final req = http.Request('GET', Uri.parse('https://api.github.com$base/actions/jobs/${bad['id']}/logs'))
        ..headers.addAll(_h)
        ..followRedirects = false;
      var res = await http.Response.fromStream(await req.send());
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers['location'] != null) {
        res = await http.get(Uri.parse(res.headers['location']!));
      }
      if (res.statusCode == 200) {
        log = _trimLog(utf8.decode(res.bodyBytes, allowMalformed: true));
      } else {
        log = 'Could not download the build log (${res.statusCode}). Open: $url';
      }
    } catch (e) {
      log = 'Could not download the build log: $e';
    }
    return BuildFailure(url, step, log);
  }

  String _trimLog(String raw) {
    final ts = RegExp(r'^\d{4}-\d\d-\d\dT[\d:.]+Z ');
    final lines = [for (final l in raw.split('\n')) l.replaceFirst(ts, '').trimRight()];
    final cut = lines.length > 300 ? lines.length - 300 : 0;
    final head = lines.sublist(0, cut);
    final tail = lines.sublist(cut);
    final err = RegExp(r'error|FAILURE|What went wrong|Could not|e: ', caseSensitive: false);
    final early = head.where(err.hasMatch).take(60).toList();
    var out = [
      if (early.isNotEmpty) ...['[earlier error lines]', ...early],
      '[last lines]',
      ...tail,
    ].join('\n');
    if (out.length > 24000) out = out.substring(out.length - 24000);
    return out;
  }

  /// Triggers the build workflow, waits, and returns the APK bytes.
  Future<List<int>> buildApk(Project p) async {
    final t = await _target(p);
    final base = '/repos/${t.owner}/${t.repo}';
    final wf = await _workflowFile(base);
    final started = DateTime.now().toUtc().subtract(const Duration(seconds: 60));
    log('Starting build on GitHub…');
    final d = await _req('POST', '$base/actions/workflows/$wf/dispatches', {'ref': t.branch});
    if (d.statusCode != 204) {
      throw Exception('Could not start build (${d.statusCode}): ${_msg(d.body)}\n'
          'Upload the project first, and make sure your token has the "workflow" scope.');
    }
    int? runId;
    var done = false;
    for (var i = 0; i < 200 && !done; i++) {
      await Future.delayed(const Duration(seconds: 6));
      final r = _ok(await _req('GET',
          '$base/actions/workflows/$wf/runs?event=workflow_dispatch&per_page=5'));
      final runs = (r['workflow_runs'] as List)
          .where((x) => DateTime.parse(x['created_at'] as String).isAfter(started))
          .toList();
      if (runs.isEmpty) {
        log('Waiting for the run to appear…');
        continue;
      }
      final run = runs.first;
      runId = run['id'] as int;
      if (run['status'] == 'completed') {
        if (run['conclusion'] != 'success') {
          throw await _failure(base, run['id'] as int, run['html_url'] as String);
        }
        done = true;
      } else {
        log('Building… (${run['status']})');
      }
    }
    if (!done || runId == null) throw Exception('Build timed out.');
    final arts = _ok(await _req('GET', '$base/actions/runs/$runId/artifacts'));
    final list = arts['artifacts'] as List;
    if (list.isEmpty) throw Exception('Build finished but produced no artifact.');
    final url = list.first['archive_download_url'] as String;
    log('Downloading APK…');
    final req = http.Request('GET', Uri.parse(url))
      ..headers.addAll(_h)
      ..followRedirects = false;
    var res = await http.Response.fromStream(await req.send());
    if (res.statusCode >= 300 && res.statusCode < 400 && res.headers['location'] != null) {
      res = await http.get(Uri.parse(res.headers['location']!));
    }
    if (res.statusCode != 200) throw Exception('Download failed (${res.statusCode}).');
    final arc = ZipDecoder().decodeBytes(res.bodyBytes);
    for (final f in arc.files) {
      if (f.isFile && f.name.endsWith('.apk')) return f.content as List<int>;
    }
    throw Exception('No .apk found inside the build artifact.');
  }
}
