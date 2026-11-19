#!/usr/bin/env python3
"""Split Oracle's EPM_AI_Skills.zip into two skill zips that Claude accepts.

Claude rejects a skill zip with more than 200 files, and Oracle's package has
about 250. This writes epm-core.zip (Core + Auth) and epm-planning.zip
(Platform + Strategic Modeling + Auth). No Oracle file is edited except the
root SKILL.md, which gets a new name, description and a short note in each zip.

    python3 split_for_claude.py ~/Downloads/EPM_AI_Skills.zip --out ~/Downloads
"""

import argparse
import sys
import zipfile
from pathlib import Path, PurePosixPath

MAX_FILES = 200
MAX_DESCRIPTION = 500

# Auth links to these four Core files, so the planning zip carries them too.
CORE_REFERENCES = [
    "Core/references/environment-registry.schema.json",
    "Core/references/execution-environment.md",
    "Core/references/security.md",
    "Core/references/trusted-proxy.md",
]

PACKAGES = {
    "epm-core": {
        "folders": ["Core/", "Auth/"],
        "files": [],
        "description": (
            "Oracle Cloud EPM infrastructure administration and shared authentication. "
            "Use for users, groups, access and security administration, access logs and "
            "activity reports, files and SFTP, migration snapshots, maintenance windows, "
            "outbound mail, feedback, Essbase exports and block analysis, query governor, "
            "concurrent usage simulation, and EPM auth setup (SSO, Basic, OAuth). Planning "
            "and Strategic Modeling are in the separate epm-planning skill."
        ),
        "note": (
            "> **Split package note.** This upload holds Core and Auth only. Planning\n"
            "> (`Platform/`) and Strategic Modeling (`StrategicModeling/`) live in the\n"
            "> separate `epm-planning` skill. Links to those folders below will not resolve\n"
            "> here; hand those requests to `epm-planning`.\n"
        ),
    },
    "epm-planning": {
        "folders": ["Platform/", "StrategicModeling/", "Auth/"],
        "files": CORE_REFERENCES,
        "description": (
            "Oracle Cloud EPM Planning and Strategic Modeling with shared authentication. "
            "Use for Planning data exploration, forms, dimensions and members, business "
            "concepts, Planning REST tasks (applications, plan types, jobs, rules, substitution "
            "variables), Strategic Modeling models, accounts, scenarios and EPS workflows, and "
            "EPM auth setup. Infrastructure administration (users, groups, security, snapshots, "
            "files, mail, Essbase) is in the separate epm-core skill."
        ),
        "note": (
            "> **Split package note.** This upload holds Platform (Planning),\n"
            "> StrategicModeling and Auth, plus the four Core reference files Auth links to\n"
            "> (`Core/references/`). Core infrastructure operations live in the separate\n"
            "> `epm-core` skill. Links to `Core/Core-SKILL.md` will not resolve here; hand\n"
            "> infrastructure requests to `epm-core`.\n"
        ),
    },
}

SHARED_FILES = ["agents/openai.yaml"]


def find_root(names):
    """Return the folder prefix that holds the router SKILL.md, e.g. 'EPM_Agent_Skills/'."""
    for name in names:
        path = PurePosixPath(name)
        if path.name == "SKILL.md" and len(path.parts) <= 2:
            return "" if len(path.parts) == 1 else path.parts[0] + "/"
    sys.exit("No root SKILL.md found. Pass the EPM AI Skills zip from Settings and Actions > Downloads.")


def rewrite_router(text, name, description, note):
    """Swap the frontmatter name and description, then add the split note."""
    if not text.startswith("---\n"):
        sys.exit("Root SKILL.md has no frontmatter. Did Oracle change the package layout?")
    end = text.index("\n---\n", 4)
    body = text[end + len("\n---\n"):]
    header = f"---\nname: {name}\ndescription: {description}\n---\n"
    return header + "\n" + note + "\n" + body.lstrip("\n")


def build(source, root, name, spec, out_dir):
    members = {}
    for info in source.infolist():
        if info.is_dir() or not info.filename.startswith(root):
            continue
        rel = info.filename[len(root):]
        if "__MACOSX" in rel or rel.endswith(".DS_Store"):
            continue
        if rel in SHARED_FILES or rel in spec["files"] or rel.startswith(tuple(spec["folders"])):
            members[rel] = info

    missing = [f for f in spec["files"] + SHARED_FILES if f not in members]
    if missing:
        sys.exit(f"{name}: expected files are missing from the package: {', '.join(missing)}")

    count = len(members) + 1
    if count > MAX_FILES:
        sys.exit(f"{name}: {count} files is over Claude's limit of {MAX_FILES}.")
    if len(spec["description"]) > MAX_DESCRIPTION:
        sys.exit(f"{name}: description is over Claude's {MAX_DESCRIPTION}-character limit.")

    router = source.read(root + "SKILL.md").decode("utf-8")
    target = out_dir / f"{name}.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as dest:
        dest.writestr(f"{name}/SKILL.md", rewrite_router(router, name, spec["description"], spec["note"]))
        for rel in sorted(members):
            info = members[rel]
            copy = zipfile.ZipInfo(f"{name}/{rel}", date_time=info.date_time)
            copy.compress_type = zipfile.ZIP_DEFLATED
            copy.external_attr = info.external_attr
            dest.writestr(copy, source.read(info))
    print(f"{target}  {count} files")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("package", type=Path, help="EPM_AI_Skills.zip from Settings and Actions > Downloads")
    parser.add_argument("--out", type=Path, default=Path.cwd(), help="folder for the two zips (default: current folder)")
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.package) as source:
        root = find_root(source.namelist())
        for name, spec in PACKAGES.items():
            build(source, root, name, spec, args.out)


if __name__ == "__main__":
    main()
