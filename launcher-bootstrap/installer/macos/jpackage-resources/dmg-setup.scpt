-- Override jpackage's default DMGsetup.scpt.
-- Default calls "open theDisk", which pops a Finder window mid-package.
-- Keep Applications alias; skip window open / layout / delay.
tell application "Finder"
	make new alias file at POSIX file "DEPLOY_VOLUME_PATH" to POSIX file "DEPLOY_INSTALL_LOCATION" with properties {name:"Applications"}
	update (disks whose URL = "DEPLOY_VOLUME_URL") without registering applications
end tell
