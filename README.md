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
| [Databricks JDBC for Essbase 21c](https://epmnerd.com/blog/databricks-jdbc-essbase-21c) (series) | [`posts/essbase-databricks-jdbc`](posts/essbase-databricks-jdbc) |

No credentials. Named Connections in the EPM app hold the host and Basic auth. The Databricks JAR in this repo is an unofficial Java 8 build. Put the client secret in an environment variable or in Essbase, not in a file here.
