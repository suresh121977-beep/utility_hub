import 'dart:convert';
import 'package:http/http.dart' as http;
import 'bundle.dart';

const defaultModel = 'claude-sonnet-5-5';

const _system = '''You edit software projects. The user sends a project as a bundle where every file starts with a line "=== FILE: path ===" followed by its full contents, then a requested change.
Reply with ONLY the files you changed or created, in exactly the same bundle format, each with its COMPLETE new contents (never partial snippets or "..." placeholders). Do not include unchanged files. No commentary, no markdown code fences.''';

class Ai {
  /// Returns only the changed/new files (path -> full content).
  static Future<Map<String, String>> update(
      String apiKey, String model, Project p, String request) async {
    final body = {
      'model': model,
      'max_tokens': 16000,
      'system': _system,
      'messages': [
        {
          'role': 'user',
          'content':
              'Project bundle:\n\n${makeBundle(p.text)}\n\n---\nRequested change: $request'
        }
      ],
    };
    final res = await http
        .post(
          Uri.parse('https://api.anthropic.com/v1/messages'),
          headers: {
            'x-api-key': apiKey,
            'anthropic-version': '2023-06-01',
            'content-type': 'application/json',
          },
          body: jsonEncode(body),
        )
        .timeout(const Duration(minutes: 10));
    final data = jsonDecode(utf8.decode(res.bodyBytes));
    if (res.statusCode != 200) {
      throw Exception('AI error ${res.statusCode}: ${data['error']?['message'] ?? res.body}');
    }
    if (data['stop_reason'] == 'max_tokens') {
      throw Exception('The AI response was cut off. Ask for a smaller change.');
    }
    var text = (data['content'] as List)
        .where((c) => c['type'] == 'text')
        .map((c) => c['text'])
        .join('\n')
        .trim();
    if (text.startsWith('```')) text = text.substring(text.indexOf('\n') + 1);
    if (text.endsWith('```')) text = text.substring(0, text.lastIndexOf('```'));
    final files = parseBundle(text);
    if (files.isEmpty) throw Exception('The AI returned no files:\n$text');
    return files;
  }
}

const _diagSystem = """You are a build-failure expert for Flutter and Android projects built by GitHub Actions. The user sends a project bundle (each file starts with a line "=== FILE: path ===") and the log of the failed build. Find the exact root cause. Reply in EXACTLY this format, with no markdown fences and no other commentary:

REASON:
2-5 plain sentences: what failed and why, naming the file and line when known.
=== FIX: path/of/file ===
KIND: edit
NOTE: one sentence describing the change
--- FIND ---
exact text currently in the file (must match exactly and be unique in the file)
--- REPLACE ---
the new text
=== END ===
=== FIX: path/of/other/file ===
KIND: whole
NOTE: one sentence describing the change
--- CONTENT ---
the COMPLETE new contents of the file (never partial, never "..." placeholders)
=== END ===

Use KIND edit when the file needs only one or two small changes. Use KIND whole when the file needs larger changes or is new. List only files that must change, at most 6. If the cause is not in the project files (for example a bad token, network problem or GitHub outage), write only the REASON and no FIX blocks.""";

class Fix {
  final String path, kind, note, find, replace, content;
  Fix(this.path, this.kind, this.note, this.find, this.replace, this.content);
  bool get whole => kind == 'whole';

  /// New file text, or null when an edit cannot be applied to [current].
  String? apply(String? current) {
    if (whole) return content;
    if (current == null || find.isEmpty || !current.contains(find)) return null;
    return current.replaceFirst(find, replace);
  }
}

class Diagnosis {
  final String reason;
  final List<Fix> fixes;
  Diagnosis(this.reason, this.fixes);
}

String _sec(String seg, String name) {
  final m = RegExp('^--- $name ---\\n', multiLine: true).firstMatch(seg);
  if (m == null) return '';
  var rest = seg.substring(m.end);
  final n = RegExp(r'^--- (FIND|REPLACE|CONTENT) ---$', multiLine: true).firstMatch(rest);
  if (n != null) rest = rest.substring(0, n.start);
  return rest.replaceFirst(RegExp(r'\n$'), '');
}

Diagnosis parseDiagnosis(String t) {
  final hdr = RegExp(r'^=== FIX: (.+?) ===$', multiLine: true);
  final ms = hdr.allMatches(t).toList();
  var reason = (ms.isEmpty ? t : t.substring(0, ms.first.start)).trim();
  reason = reason.replaceFirst(RegExp(r'^REASON:\s*'), '');
  final fixes = <Fix>[];
  for (var i = 0; i < ms.length; i++) {
    final end = i + 1 < ms.length ? ms[i + 1].start : t.length;
    var seg = t.substring(ms[i].end + 1 > end ? end : ms[i].end + 1, end);
    seg = seg.replaceFirst(RegExp(r'\n?=== END ===\s*$'), '\n');
    final kind = RegExp(r'^KIND:\s*(\w+)', multiLine: true).firstMatch(seg)?.group(1)?.toLowerCase() ?? 'edit';
    final note = RegExp(r'^NOTE:\s*(.+)$', multiLine: true).firstMatch(seg)?.group(1)?.trim() ?? '';
    var content = _sec(seg, 'CONTENT');
    if (content.isNotEmpty && !content.endsWith('\n')) content += '\n';
    fixes.add(Fix(ms[i].group(1)!.trim(), kind == 'whole' ? 'whole' : 'edit', note,
        _sec(seg, 'FIND'), _sec(seg, 'REPLACE'), content));
  }
  return Diagnosis(reason, fixes);
}

Future<Diagnosis> diagnoseBuild(
    String apiKey, String model, Project p, String step, String log) async {
  final body = {
    'model': model,
    'max_tokens': 16000,
    'system': _diagSystem,
    'messages': [
      {
        'role': 'user',
        'content': 'Project bundle:\n\n${makeBundle(p.text)}\n\n---\nFailed step: $step\n\nBuild log:\n$log'
      }
    ],
  };
  final res = await http
      .post(
        Uri.parse('https://api.anthropic.com/v1/messages'),
        headers: {
          'x-api-key': apiKey,
          'anthropic-version': '2023-06-01',
          'content-type': 'application/json',
        },
        body: jsonEncode(body),
      )
      .timeout(const Duration(minutes: 10));
  final data = jsonDecode(utf8.decode(res.bodyBytes));
  if (res.statusCode != 200) {
    throw Exception('AI error ${res.statusCode}: ${data['error']?['message'] ?? res.body}');
  }
  if (data['stop_reason'] == 'max_tokens') {
    throw Exception('The AI answer was cut off. Open the build log on GitHub and ask for a smaller change.');
  }
  var text = (data['content'] as List)
      .where((c) => c['type'] == 'text')
      .map((c) => c['text'])
      .join('\n')
      .trim();
  if (text.startsWith('```')) text = text.substring(text.indexOf('\n') + 1);
  if (text.endsWith('```')) text = text.substring(0, text.lastIndexOf('```'));
  return parseDiagnosis(text.trim());
}
