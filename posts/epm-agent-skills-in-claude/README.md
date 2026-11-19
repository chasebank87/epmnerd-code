# Oracle EPM Agent Skills in Claude

Companion files for [Oracle EPM Agent Skills in Claude: install, auth, and the 200-file limit](https://epmnerd.com/blog/epm-agent-skills-in-claude).

## split_for_claude.py

Claude rejects a skill zip with more than 200 files. Oracle's `EPM_AI_Skills.zip` from **Settings and Actions > Downloads** has 250, so the upload fails with "Zip contains too many files (maximum 200)".

The script splits the package into two skills that Claude accepts:

| Zip | Holds | Files |
| --- | --- | --- |
| `epm-core.zip` | Core and Auth | 124 |
| `epm-planning.zip` | Planning (Platform), Strategic Modeling, Auth, and the four Core reference files Auth links to | 162 |

Oracle's files are copied as they are. The only change is the root `SKILL.md` in each zip, which gets its own `name`, a description, and a note pointing at the other skill.

```bash
python3 split_for_claude.py ~/Downloads/EPM_AI_Skills.zip --out ~/Downloads
```

Any Python 3, no packages to install. The script stops if a zip would go over 200 files or a description over Claude's 500-character limit. Upload both zips in Claude under **Plugins > Add > Upload plugin**.

Claude Code has no file limit. Unzip Oracle's package into `~/.claude/skills/` instead and skip the script.

## Auth templates

The `examples/` folder has the Basic configuration file in the format the Auth skill expects, one field per line. Fill it in on your machine. Never paste a password or token into the chat.

| File | Use |
| --- | --- |
| `examples/basic-config.txt` | One environment, Basic auth |
| `examples/basic-config-multiple.txt` | Several environments in one file |
| `examples/sso-prompt.txt` | Device-code SSO prompt with a client ID and service instance ID |
| `examples/refresh-token-prompt.txt` | OAuth refresh-token prompt |
