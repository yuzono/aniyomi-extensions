# Stremio

Table of Content
- [Extension settings](#extension-settings)
- [Browsing](#browsing)

## Extension settings

### WebUI Url

Url used when opening an entry in webview. Does not affect any other part of the extension

### Server Url

Server url used for torrent streaming. If no server url is set, torrents will be played with a magnet link, which not all players support

### Addons

If not logged in, addons must be specified manually. Enter in manifest urls (urls ending with `manifest.json`), either stremio:// or http(s):// links are supported.

### Email

Email address for account

### Password

Password for account

### Episode name format

Format the episode name

### Scanlator

Format the scanlator

### Skip season 0

Filter out any episodes with season set to 0, which usually means they're specials

### Log out

Log out, requires restart

### Fetch library

If logged in, the user's library can be browsed. Please only keep it enabled if you intend to add entries from your account's library, to prevent unnecessary strain on stremio's servers.

## Browsing

Due to some limitations with the Aniyomi API, browsing is quite different from stremio. To browse from a different catalog, first the `Reset` button in filters must be pressed to load in the catalogs, then each catalog will be displayed in the format `<Addon name> - <Catalog type> - <Catalog name>`. Additionally, some flags might be present in the catalog name (letters in the parentheses at the end). ´g´ means that the catalog supports genre search, and `s` means that the catalog supports searching. If the letter is uppercase, that means that the genre/search term is required. To load in the genres, first select the catalog and press `Filter`. After the entries have loaded in, press `Reset` in the filter tab to load in the genres.