# epmnerd-code

Groovy, REST payloads, and other paste-ready files from [epmnerd.com](https://epmnerd.com) posts.

Standalone posts use a flat folder matching the slug. Multi-part series use one bucket for the series and a folder per part:

```text
posts/<post-slug>/
  README.md
  <one or two files>

posts/<series-id>/
  README.md
  <part-slug>/
    README.md
    <files>
```

```bash
git clone https://github.com/chasebank87/epmnerd-code.git
```

## Posts

| Post | Folder |
| --- | --- |
| [EPM REST from Groovy via Connections](https://epmnerd.com/blog/named-connections-for-epm-rest) (series) | [`posts/epm-rest-from-groovy`](posts/epm-rest-from-groovy) |

No credentials. Named Connections in the EPM app hold the host and Basic auth.
