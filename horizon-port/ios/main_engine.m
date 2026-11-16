#import <Foundation/Foundation.h>

extern int horizon_ios_run(const char *resources, const char *game_dir);

// The engine app has no UIKit shell: winit's iOS backend calls
// `UIApplicationMain` itself and creates the window from Rust.
int main(int argc, char *argv[]) {
    @autoreleasepool {
        NSFileManager *manager = [NSFileManager defaultManager];
        NSArray<NSString *> *documents =
            NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES);
        NSString *gameDir = documents.firstObject ?: NSTemporaryDirectory();
        [manager createDirectoryAtPath:gameDir
           withIntermediateDirectories:YES
                            attributes:nil
                                 error:nil];

        NSString *bundleResources =
            [[[NSBundle mainBundle] resourcePath] stringByAppendingPathComponent:@"horizon-client/26.3"];
        NSString *documentsResources =
            [gameDir stringByAppendingPathComponent:@"horizon-client/26.3"];
        [manager createDirectoryAtPath:documentsResources
           withIntermediateDirectories:YES
                            attributes:nil
                                 error:nil];

        // Imported resources in Documents win over the empty placeholder in
        // the bundle. Files app can see Documents because Info.plist enables
        // UIFileSharingEnabled.
        NSString *marker =
            [documentsResources stringByAppendingPathComponent:@"horizon-resources.json"];
        NSString *resources = [manager fileExistsAtPath:marker] ? documentsResources : bundleResources;

        setenv("HORIZON_HOME", gameDir.UTF8String, 1);
        setenv("HORIZON_NO_AUDIO", "1", 1);
        setenv("HORIZON_NO_DISCORD", "1", 1);

        return horizon_ios_run(resources.UTF8String, gameDir.UTF8String);
    }
}
