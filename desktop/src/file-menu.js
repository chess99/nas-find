// The native menu returns an index into these captured actions. Absolute paths and
// the chosen application remain on the validated native side.
let nativeMenuBusy = false;
export async function popupFileMenu(items, context, {isMac, invoke, popup, notify}) {
  if (isMac && context?.file) {
    if (nativeMenuBusy) return;
    nativeMenuBusy = true;
    try {
      let result;
      try {
        result = await invoke('show_file_menu', {
          path: context.file.path,
          position: context.position || null,
          items: items.map((item, index) => ({index, text: item.text, enabled: item.enabled !== false,
            separator: !!item.separator, native: item.native})),
        });
      } catch (error) {
        // Offline shares must not take away index-only copy / export actions.
        notify(String(error));
        return popup(items);
      }
      if (Number.isInteger(result.index)) {
        const item = items[result.index];
        if (item && !item.separator && item.enabled !== false) await item.action();
      }
      return;
    } finally {
      nativeMenuBusy = false;
    }
  }
  return popup(items);
}
