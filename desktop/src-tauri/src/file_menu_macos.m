#import <AppKit/AppKit.h>
#import <CoreServices/CoreServices.h>
#import <Quartz/Quartz.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>

// Only public macOS APIs are used. Finder Sync / Finder Quick Actions are
// hosted by Finder and cannot be imported as an arbitrary file's NSMenu.
static NSString *const NFLegacyFilenames = @"NSFilenamesPboardType";

static NSString *NFApplicationKey(NSURL *url) {
    return [NSBundle bundleWithURL:url].bundleIdentifier ?: url.URLByResolvingSymlinksInPath.path;
}

static NSArray<NSURL *> *NFApplications(NSURL *file, NSURL **defaultApp) {
    NSWorkspace *workspace = NSWorkspace.sharedWorkspace;
    NSArray<NSURL *> *applications;
    // Capability detection also works with Rust's -nodefaultlibs link mode,
    // without pulling in Clang's OS-version-check runtime.
    if ([workspace respondsToSelector:@selector(URLsForApplicationsToOpenURL:)]) {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunguarded-availability-new"
        applications = [workspace URLsForApplicationsToOpenURL:file];
#pragma clang diagnostic pop
    } else {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
        applications = CFBridgingRelease(LSCopyApplicationURLsForURL((__bridge CFURLRef)file, kLSRolesAll));
#pragma clang diagnostic pop
    }
    *defaultApp = [workspace URLForApplicationToOpenURL:file];
    // Prefer the default copy, then /Applications copies over updater caches.
    applications = [applications sortedArrayUsingComparator:^NSComparisonResult(NSURL *a, NSURL *b) {
        BOOL aInstalled = [a.path hasPrefix:@"/Applications/"] || [a.path hasPrefix:@"/System/Applications/"];
        BOOL bInstalled = [b.path hasPrefix:@"/Applications/"] || [b.path hasPrefix:@"/System/Applications/"];
        if (aInstalled != bInstalled) return aInstalled ? NSOrderedAscending : NSOrderedDescending;
        return [a.path localizedStandardCompare:b.path];
    }];
    NSMutableArray<NSURL *> *result = [NSMutableArray array];
    NSMutableSet<NSString *> *seen = [NSMutableSet set];
    if (*defaultApp) {
        [result addObject:*defaultApp];
        [seen addObject:NFApplicationKey(*defaultApp)];
    }
    for (NSURL *application in applications) {
        NSString *key = NFApplicationKey(application);
        if ([seen containsObject:key] || ![application checkResourceIsReachableAndReturnError:nil]) continue;
        [seen addObject:key];
        [result addObject:application];
    }
    if (result.count > 1) {
        NSUInteger start = *defaultApp ? 1 : 0;
        NSArray *rest = [[result subarrayWithRange:NSMakeRange(start, result.count - start)]
            sortedArrayUsingComparator:^NSComparisonResult(NSURL *a, NSURL *b) {
                return [[NSFileManager.defaultManager displayNameAtPath:a.path]
                    localizedStandardCompare:[NSFileManager.defaultManager displayNameAtPath:b.path]];
            }];
        [result replaceObjectsInRange:NSMakeRange(start, rest.count) withObjectsFromArray:rest];
    }
    return result;
}

@interface NFFileMenuView : NSView <QLPreviewPanelDataSource, NSSharingServicePickerDelegate, NSSharingServiceDelegate>
@property NSURL *file;
@property NSDictionary *result;
@property NSSharingServicePicker *picker;
@property (weak) NSResponder *previousResponder;
@property (weak) NSWindow *ownerWindow;
@end

static NSMutableSet<NFFileMenuView *> *NFSharingOwners;

@implementation NFFileMenuView
- (BOOL)acceptsFirstResponder { return YES; }
- (void)choose:(NSMenuItem *)sender { self.result = sender.representedObject; }
- (id)validRequestorForSendType:(NSPasteboardType)sendType returnType:(NSPasteboardType)returnType {
    if ((!returnType || returnType.length == 0) &&
        ([sendType isEqualToString:NSPasteboardTypeFileURL] || [sendType isEqualToString:NFLegacyFilenames])) return self;
    return nil;
}
- (BOOL)writeSelectionToPasteboard:(NSPasteboard *)pasteboard types:(NSArray<NSPasteboardType> *)types {
    [pasteboard clearContents];
    if ([types containsObject:NSPasteboardTypeFileURL]) return [pasteboard writeObjects:@[self.file]];
    if ([types containsObject:NFLegacyFilenames]) {
        [pasteboard declareTypes:@[NFLegacyFilenames] owner:nil];
        return [pasteboard setPropertyList:@[self.file.path] forType:NFLegacyFilenames];
    }
    return NO;
}
- (void)restoreResponder {
    if (self.ownerWindow.firstResponder == self) [self.ownerWindow makeFirstResponder:self.previousResponder];
    [self removeFromSuperview];
}
- (BOOL)acceptsPreviewPanelControl:(QLPreviewPanel *)panel { return YES; }
- (void)beginPreviewPanelControl:(QLPreviewPanel *)panel { panel.dataSource = self; }
- (void)endPreviewPanelControl:(QLPreviewPanel *)panel {
    panel.dataSource = nil;
    [self restoreResponder];
}
- (NSInteger)numberOfPreviewItemsInPreviewPanel:(QLPreviewPanel *)panel { return 1; }
- (id<QLPreviewItem>)previewPanel:(QLPreviewPanel *)panel previewItemAtIndex:(NSInteger)index { return self.file; }
- (void)sharingServicePicker:(NSSharingServicePicker *)picker didChooseSharingService:(NSSharingService *)service {
    if (service) service.delegate = self;
    else [NFSharingOwners removeObject:self];
}
- (void)sharingService:(NSSharingService *)service didShareItems:(NSArray *)items { [NFSharingOwners removeObject:self]; }
- (void)sharingService:(NSSharingService *)service didFailToShareItems:(NSArray *)items error:(NSError *)error {
    [NFSharingOwners removeObject:self];
    NSAlert *alert = [[NSAlert alloc] init];
    alert.messageText = @"共享未完成";
    alert.informativeText = error.localizedDescription ?: @"请检查文件是否可访问后重试。";
    [alert runModal];
}
@end

static NSMenuItem *NFItem(NSMenu *menu, NFFileMenuView *owner, NSString *title, NSDictionary *result) {
    NSMenuItem *item = [[NSMenuItem alloc] initWithTitle:title action:@selector(choose:) keyEquivalent:@""];
    item.target = owner;
    item.representedObject = result;
    [menu addItem:item];
    return item;
}

static NSMenu *NFOpenWithMenu(NFFileMenuView *owner) {
    NSMenu *menu = [[NSMenu alloc] initWithTitle:@"打开方式"];
    menu.autoenablesItems = NO;
    NSURL *defaultApp;
    NSArray<NSURL *> *applications = NFApplications(owner.file, &defaultApp);
    for (NSURL *application in applications) {
        BOOL isDefault = [application isEqual:defaultApp];
        NSString *name = [NSFileManager.defaultManager displayNameAtPath:application.path];
        NSMenuItem *item = NFItem(menu, owner, isDefault ? [name stringByAppendingString:@"（默认）"] : name,
            @{@"application": application.path});
        NSImage *icon = [NSWorkspace.sharedWorkspace iconForFile:application.path];
        icon.size = NSMakeSize(16, 16);
        item.image = icon;
        if (isDefault && applications.count > 1) [menu addItem:NSMenuItem.separatorItem];
    }
    if (applications.count) [menu addItem:NSMenuItem.separatorItem];
    NFItem(menu, owner, @"其他应用…", @{@"native": @"other"});
    return menu;
}

static NSMenu *NFBuildMenu(NFFileMenuView *owner, NSArray<NSDictionary *> *items, NSMenu *services, BOOL directory) {
    NSMenu *menu = [[NSMenu alloc] initWithTitle:@"文件操作"];
    menu.autoenablesItems = NO;
    for (NSDictionary *entry in items) {
        if ([entry[@"separator"] boolValue]) { [menu addItem:NSMenuItem.separatorItem]; continue; }
        NSString *title = entry[@"text"] ?: @"";
        NSMenuItem *item;
        if ([entry[@"native"] isEqualToString:@"open_with"]) {
            item = [[NSMenuItem alloc] initWithTitle:@"打开方式" action:nil keyEquivalent:@""];
            if (!directory) item.submenu = NFOpenWithMenu(owner);
            item.enabled = !directory;
            [menu addItem:item];
            NFItem(menu, owner, @"快速查看", @{@"native": @"quicklook"});
            NFItem(menu, owner, @"共享…", @{@"native": @"share"});
            NSMenuItem *serviceItem = [[NSMenuItem alloc] initWithTitle:@"服务" action:nil keyEquivalent:@""];
            serviceItem.submenu = services;
            [menu addItem:serviceItem];
        } else {
            item = NFItem(menu, owner, title, @{@"index": entry[@"index"]});
            item.enabled = entry[@"enabled"] ? [entry[@"enabled"] boolValue] : YES;
        }
    }
    return menu;
}

// The returned UTF-8 JSON is owned by Rust and freed with libc::free.
// AppKit is always called on the owning Tauri window's main thread.
char *nf_file_menu(void *windowPointer, const char *path, const char *itemsJSON, double x, double y) {
    @autoreleasepool {
        NFFileMenuView *owner;
        NSMenu *previousServices = NSApp.servicesMenu;
        BOOL preview = NO;
        @try {
            NSWindow *window = (__bridge NSWindow *)windowPointer;
            NSURL *file = [NSURL fileURLWithPath:[NSString stringWithUTF8String:path]];
            NSData *data = [[NSString stringWithUTF8String:itemsJSON] dataUsingEncoding:NSUTF8StringEncoding];
            NSArray<NSDictionary *> *items = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
            if (!window || ![items isKindOfClass:NSArray.class]) return strdup("{\"error\":\"文件菜单参数无效\"}");
            [NSApp activateIgnoringOtherApps:YES];
            [window makeKeyAndOrderFront:nil];
            NSNumber *directory;
            [file getResourceValue:&directory forKey:NSURLIsDirectoryKey error:nil];
            owner = [[NFFileMenuView alloc] initWithFrame:NSZeroRect];
            owner.file = file;
            owner.result = @{};
            owner.previousResponder = window.firstResponder;
            owner.ownerWindow = window;
            [window.contentView addSubview:owner];
            [window makeFirstResponder:owner];

            NSMenu *services = [[NSMenu alloc] initWithTitle:@"服务"];
            [NSApp registerServicesMenuSendTypes:@[NSPasteboardTypeFileURL, NFLegacyFilenames] returnTypes:@[]];
            NSApp.servicesMenu = services;
            NSMenu *menu = NFBuildMenu(owner, items, services, directory.boolValue);
            @try {
                if (x >= 0 && y >= 0) {
                    NSPoint point = NSMakePoint(x, window.contentView.isFlipped ? y : window.contentView.bounds.size.height - y);
                    [menu popUpMenuPositioningItem:nil atLocation:point inView:window.contentView];
                } else {
                    [menu popUpMenuPositioningItem:nil atLocation:NSEvent.mouseLocation inView:nil];
                }
            } @finally {
                NSApp.servicesMenu = previousServices;
            }
            NSString *native = owner.result[@"native"];
            if ([native isEqualToString:@"quicklook"]) {
                [[QLPreviewPanel sharedPreviewPanel] makeKeyAndOrderFront:nil];
                preview = YES;
            } else {
                [owner restoreResponder];
                if ([native isEqualToString:@"other"]) {
                    NSOpenPanel *panel = NSOpenPanel.openPanel;
                    panel.title = @"选择打开此文件的应用";
                    panel.prompt = @"打开";
                    panel.allowedContentTypes = @[UTTypeApplicationBundle];
                    panel.directoryURL = [NSURL fileURLWithPath:@"/Applications" isDirectory:YES];
                    panel.canChooseDirectories = NO;
                    panel.allowsMultipleSelection = NO;
                    panel.treatsFilePackagesAsDirectories = NO;
                    if ([panel runModal] == NSModalResponseOK) owner.result = @{@"application": panel.URL.path};
                    else owner.result = @{};
                } else if ([native isEqualToString:@"share"]) {
                    if (!NFSharingOwners) NFSharingOwners = [NSMutableSet set];
                    [NFSharingOwners addObject:owner];
                    owner.picker = [[NSSharingServicePicker alloc] initWithItems:@[file]];
                    owner.picker.delegate = owner;
                    NSPoint point = [window.contentView convertPoint:[window convertPointFromScreen:NSEvent.mouseLocation] fromView:nil];
                    [owner.picker showRelativeToRect:NSMakeRect(point.x, point.y, 1, 1)
                        ofView:window.contentView preferredEdge:NSMinYEdge];
                }
            }
            NSData *result = [NSJSONSerialization dataWithJSONObject:owner.result options:0 error:nil];
            return strdup([[NSString alloc] initWithData:result encoding:NSUTF8StringEncoding].UTF8String);
        } @catch (NSException *exception) {
            return strdup("{\"error\":\"macOS 文件菜单未能完成，请重试\"}");
        } @finally {
            NSApp.servicesMenu = previousServices;
            if (!preview) [owner restoreResponder];
        }
    }
}
