#!/usr/bin/env python3
"""Builds the HTML caption of a release post in the Telegram channel.

usage: tg-caption.py <version> <ext_sha> <inugram_tag> <release_tag> [<old_ext_sha> <exteraless_checkout>]
The first line must stay "MD3 Player <version>": the plugin's update check parses it.
"""
import html
import subprocess
import sys

REPO = "mrvokintos-plugins/md3-player"
EXTERALESS = "exteraless/exteraless"
INUGRAM = "teidesu/inugram"
PATHS = [
    "TMessagesProj/src/main/java/app/exteraless/player",
    "TMessagesProj/src/main/kotlin/app/exteraless/appearance/AppearanceConfig.kt",
    ":(glob)TMessagesProj/src/main/res/values*/strings_oe_player.xml",
]
LIMIT = 1000  # Telegram counts visible characters, 1024 max


def link(url, text):
    return f'<a href="{html.escape(url)}">{html.escape(text)}</a>'


def changes(old, new, checkout):
    if not old or not checkout:
        return []
    try:
        out = subprocess.run(
            ["git", "-C", checkout, "log", "--format=%H%x09%s", f"{old}..{new}", "--", *PATHS],
            check=True, capture_output=True, text=True,
        ).stdout
    except subprocess.CalledProcessError:
        return []
    rows = []
    for line in out.splitlines():
        sha, _, subject = line.partition("\t")
        rows.append((sha, subject))
    return rows


def main():
    version, ext, inu_tag, release_tag = sys.argv[1:5]
    old = sys.argv[5] if len(sys.argv) > 5 else ""
    checkout = sys.argv[6] if len(sys.argv) > 6 else ""

    head = [
        f"<b>MD3 Player {html.escape(version)}</b>",
        "exteraless " + link(f"https://github.com/{EXTERALESS}/commit/{ext}", ext[:7])
        + " · inugram " + link(f"https://github.com/{INUGRAM}/releases/tag/{inu_tag}", inu_tag),
    ]
    foot = [
        link(f"https://github.com/{REPO}/releases/tag/{release_tag}", "Release")
        + " · " + link(f"https://github.com/{REPO}", "GitHub")
        + " · " + link(f"https://github.com/{EXTERALESS}", "exteraless"),
    ]
    visible = len(f"MD3 Player {version}") + len(f"exteraless {ext[:7]} · inugram {inu_tag}") + len("Release · GitHub · exteraless") + 6

    body = []
    rows = changes(old, ext, checkout)
    for i, (sha, subject) in enumerate(rows):
        line_visible = len(f"• {subject} ({sha[:7]})") + 1
        if visible + line_visible > LIMIT - 20:
            body.append(f"… +{len(rows) - i}")
            break
        body.append(f"• {html.escape(subject)} (" + link(f"https://github.com/{EXTERALESS}/commit/{sha}", sha[:7]) + ")")
        visible += line_visible

    parts = ["\n".join(head)]
    if body:
        parts.append("\n".join(body))
    parts.append("\n".join(foot))
    print("\n\n".join(parts))


if __name__ == "__main__":
    main()
