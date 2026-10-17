// Compile against the production AppKit implementation. Uses only synthetic
// local files and a private pasteboard, without touching NAS or default apps.
#import "../src-tauri/src/file_menu_macos.m"

static void NFCheck(BOOL condition, NSString *message) {
    if (!condition) { fprintf(stderr, "%s\n", message.UTF8String); exit(1); }
}

@interface NFMenuSmoke : NSObject <NSWindowDelegate>
@property NSWindow *window;
@property NSString *path;
@property NSString *videoPath;
@property NSTextField *status;
@end

@implementation NFMenuSmoke
- (void)windowWillClose:(NSNotification *)notification { [NSApp stop:nil]; }
- (void)showMenu:(id)sender {
    const char *items = "[{\"index\":0,\"text\":\"打开\"},{\"native\":\"open_with\"},{\"index\":2,\"text\":\"复制路径\"}]";
    char *output = nf_file_menu((__bridge void *)self.window, self.path.UTF8String, items, 30, 90);
    self.status.stringValue = [NSString stringWithUTF8String:output];
    free(output);
}
- (void)showVideoMenu:(id)sender {
    NSString *textPath = self.path;
    self.path = self.videoPath;
    [self showMenu:sender];
    self.path = textPath;
}
@end

int main(int argc, const char **argv) {
    @autoreleasepool {
        [NSApplication sharedApplication];
        NSString *directory = [NSTemporaryDirectory() stringByAppendingPathComponent:NSUUID.UUID.UUIDString];
        [NSFileManager.defaultManager createDirectoryAtPath:directory withIntermediateDirectories:YES attributes:nil error:nil];
        NSString *path = [directory stringByAppendingPathComponent:@"中文 空格 $'文件.txt"];
        [@"NAS Find 原生文件菜单验证。" writeToFile:path atomically:YES encoding:NSUTF8StringEncoding error:nil];
        NSURL *file = [NSURL fileURLWithPath:path];
        NFFileMenuView *owner = [[NFFileMenuView alloc] initWithFrame:NSZeroRect];
        owner.file = file;

        NSURL *defaultApp;
        NSArray<NSURL *> *applications = NFApplications(file, &defaultApp);
        NFCheck(applications.count > 0, @"The OS returned no applications for a text file");
        if (defaultApp) NFCheck([applications.firstObject isEqual:defaultApp], @"Default application is not first");
        NSMutableSet *keys = [NSMutableSet set];
        for (NSURL *application in applications) {
            NFCheck(![keys containsObject:NFApplicationKey(application)], @"Duplicate application bundle ID");
            [keys addObject:NFApplicationKey(application)];
        }
        NSString *videoPath = [directory stringByAppendingPathComponent:@"菜单验证 视频.mp4"];
        const unsigned char header[] = {0,0,0,24,'f','t','y','p','m','p','4','2',0,0,0,0,'m','p','4','2','i','s','o','m'};
        [[NSData dataWithBytes:header length:sizeof(header)] writeToFile:videoPath atomically:YES];
        NSURL *videoDefault;
        NSArray<NSURL *> *videoApps = NFApplications([NSURL fileURLWithPath:videoPath], &videoDefault);
        NSMutableSet *videoKeys = [NSMutableSet set];
        for (NSURL *application in videoApps) [videoKeys addObject:NFApplicationKey(application)];
        NFCheck(![keys isEqualToSet:videoKeys], @"Text and video must not both show the same unfiltered application list");
        NSMenu *openWith = NFOpenWithMenu(owner);
        NFCheck([openWith.itemArray.lastObject.title isEqualToString:@"其他应用…"], @"Other application entry missing");
        NSUInteger appCount = 0;
        for (NSMenuItem *item in openWith.itemArray) {
            if (item.representedObject[@"application"]) {
                NFCheck(item.image != nil, @"Application icon missing");
                appCount++;
            }
        }
        NFCheck(appCount == applications.count, @"Menu application count does not match registered handlers");

        NSMenu *services = [[NSMenu alloc] initWithTitle:@"服务"];
        NSArray *items = @[@{@"native":@"open_with"}, @{@"index":@7, @"text":@"复制路径"}];
        NSMenu *menu = NFBuildMenu(owner, items, services, NO);
        NFCheck([menu itemWithTitle:@"打开方式"].submenu != nil, @"Application submenu missing");
        NFCheck([menu itemWithTitle:@"快速查看"] != nil, @"Quick Look missing");
        NFCheck([menu itemWithTitle:@"共享…"] != nil, @"Sharing missing");
        NFCheck([menu itemWithTitle:@"服务"].submenu == services, @"Native Services submenu missing");
        [owner choose:[menu itemWithTitle:@"复制路径"]];
        NFCheck([owner.result[@"index"] isEqual:@7], @"Generic action index changed");
        NSMenu *folderMenu = NFBuildMenu(owner, items, [[NSMenu alloc] initWithTitle:@"服务"], YES);
        NFCheck(![folderMenu itemWithTitle:@"打开方式"].enabled, @"Application opening must be disabled for directories");

        NFCheck([owner validRequestorForSendType:NSPasteboardTypeFileURL returnType:nil] == owner, @"File URL service requestor missing");
        NFCheck([owner validRequestorForSendType:NSPasteboardTypeString returnType:nil] != owner, @"Text-only service must not receive file paths as document text");
        NSPasteboard *pasteboard = [NSPasteboard pasteboardWithUniqueName];
        NFCheck([owner writeSelectionToPasteboard:pasteboard types:@[NSPasteboardTypeFileURL]], @"Writing service file URL failed");
        NSArray<NSURL *> *read = [pasteboard readObjectsForClasses:@[NSURL.class] options:nil];
        NFCheck([read.firstObject.path isEqualToString:path], @"Unicode / special filename changed in service input");
        NFCheck([owner writeSelectionToPasteboard:pasteboard types:@[NFLegacyFilenames]], @"Legacy file service input failed");
        NFCheck([[pasteboard propertyListForType:NFLegacyFilenames] isEqual:@[path]], @"Legacy file list changed");
        [pasteboard releaseGlobally];

        puts("Native file menu: handler filtering, default ordering, deduplication, icons, actions, directory limits and service inputs passed.");
        if (argc > 1 && strcmp(argv[1], "--ui") == 0) {
            [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
            NFMenuSmoke *smoke = [[NFMenuSmoke alloc] init];
            smoke.path = path;
            smoke.videoPath = videoPath;
            smoke.window = [[NSWindow alloc] initWithContentRect:NSMakeRect(200, 300, 600, 240)
                styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable backing:NSBackingStoreBuffered defer:NO];
            smoke.window.title = @"NAS Find 文件菜单验证";
            smoke.window.releasedWhenClosed = NO;
            smoke.window.delegate = smoke;
            NSButton *button = [NSButton buttonWithTitle:@"显示文件菜单" target:smoke action:@selector(showMenu:)];
            button.frame = NSMakeRect(30, 130, 200, 40);
            [smoke.window.contentView addSubview:button];
            NSButton *videoButton = [NSButton buttonWithTitle:@"视频文件菜单" target:smoke action:@selector(showVideoMenu:)];
            videoButton.frame = NSMakeRect(270, 130, 200, 40);
            [smoke.window.contentView addSubview:videoButton];
            smoke.status = [NSTextField labelWithString:@"只使用临时测试文件"];
            smoke.status.frame = NSMakeRect(30, 30, 540, 70);
            [smoke.window.contentView addSubview:smoke.status];
            [smoke.window makeKeyAndOrderFront:nil];
            [NSApp activateIgnoringOtherApps:YES];
            [NSApp run];
        }
        [NSFileManager.defaultManager removeItemAtPath:directory error:nil];
    }
    return 0;
}
