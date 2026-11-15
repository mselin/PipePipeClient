# Release views when a screen's view is destroyed

Screens the user has left must not keep their views and images in memory.

Findings M1, M2 and M3 in `enhancements/FINDINGS.md`.

## Today

- Opening a page (a channel, a playlist, search results) puts it on top of the previous one.
  The previous page's view is destroyed, but the page object stays so Back can return to it.
- List pages (search, trending, channel videos, remote playlists, comments, related videos)
  keep a reference to their destroyed list and never detach its adapter. Every time such a
  page's view is built again, one more complete list with its rows and thumbnails stays in
  memory.
- Channel pages keep their whole destroyed view (banner, avatars, tabs, header) until the
  page itself is removed, which happens only when the user backs out of it.
- Channel banners are loaded at their full size. Every other image is limited in size.
- Result: memory grows with every channel visited. The owner, with about 1600 subscriptions,
  saw choppy playback and then an out-of-memory crash while going from channel to channel.
- The same happens, in smaller ways, on other pages: every page built on the common page
  base keeps its destroyed view through its error panel; the Subscriptions tab and the main
  page's tab bar keep earlier views each time they are built again; the search page keeps
  its suggestion list.

## Terms

- **Page**: a screen that can be returned to with Back or by switching tabs.
- **Left page**: a page whose view has been destroyed while the page still exists (it is
  under another page, or in a tab that is not shown).
- **Data**: what a page needs to show itself again without the network: its loaded items,
  channel or playlist information, scroll position.

## Acceptance criteria

- **AC-1** A left list page holds no reference to its list view, its rows or their images.
  It keeps its data.
- **AC-2** A left channel page holds no reference to any of its views: banner, avatars,
  header, tabs, buttons, lists. It keeps its data.
- **AC-2a** The same holds for every other page built on the common page base, including
  local playlists, history, bookmarks, the Subscriptions tab and the main page with its tabs.
- **AC-3** Building a page's view again never leaves an earlier view of that page in memory,
  however often it happens.
- **AC-4** Returning to a left page shows what it showed before: the same items, the same
  scroll position, the same selected tab, as today. Nothing is loaded from the network that is
  not loaded today.
- **AC-5** A result that arrives for a left page (a load that finishes, a subscription change,
  an image) does not crash the app. It is shown when the page is returned to, or dropped if
  today it would not be shown either.
- **AC-6** Channel banners are loaded no larger than the screen can show, like other images.
  A banner looks the same as today on the device's screen.
- **AC-7** Nothing else changes for the user: no new setting, no new text.
- **AC-8** With a debug build, going channel to channel (open a channel from a list, open a
  video's uploader, go back, repeat twenty times) and then waiting, LeakCanary reports no
  retained view of a left page.

## Not covered

- How many pages can pile up. Each left page still keeps its data; only its views are
  released.
- Memory the player holds for what it plays (findings M4, M5, M8).
- Costs that grow with the number of subscriptions (findings M6, J1 to J3).
