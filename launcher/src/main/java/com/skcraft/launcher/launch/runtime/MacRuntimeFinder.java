package com.skcraft.launcher.launch.runtime;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.PropertyListParser;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.skcraft.launcher.util.Environment;
import lombok.extern.java.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

@Log
public class MacRuntimeFinder implements PlatformRuntimeFinder {
	@Override
	public List<JavaRuntime> getSystemRuntimes(Environment env) {
		ArrayList<JavaRuntime> entries = Lists.newArrayList();

		entries.addAll(MinecraftJavaFinder.scanLauncherDirectories(env,
				ImmutableSet.of(new File(System.getenv("HOME"), "Library/Application Support/minecraft"))));

		try {
			Process p = Runtime.getRuntime().exec("/usr/libexec/java_home -X");
			NSArray root = (NSArray) PropertyListParser.parse(p.getInputStream());
			NSObject[] arr = root.getArray();
			for (NSObject obj : arr) {
				NSDictionary dict = (NSDictionary) obj;
				entries.add(new JavaRuntime(
						new File(dict.objectForKey("JVMHomePath").toString()).getAbsoluteFile(),
						dict.objectForKey("JVMVersion").toString(),
						isArch64Bit(dict.objectForKey("JVMArch").toString())));
			}
		} catch (Throwable err) {
			log.log(Level.WARNING, "Failed to parse java_home command", err);
		}

		return entries;
	}

	private static boolean isArch64Bit(String string) {
		return string == null || string.matches("x64|x86_64|amd64|aarch64");
	}
}
