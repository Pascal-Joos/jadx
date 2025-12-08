package jadx.core.utils.files;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.core.utils.exceptions.JadxRuntimeException;

public class FileUtils {

	private static final Logger LOG = LoggerFactory.getLogger(FileUtils.class);

	public static final int READ_BUFFER_SIZE = 8 * 1024;

	private FileUtils() {
	}

	public static void close(Closeable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (IOException e) {
				LOG.error("Error closing resource", e);
			}
		}
	}

	public static void copyStream(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[READ_BUFFER_SIZE];
		int len;
		while ((len = in.read(buffer)) != -1) {
			out.write(buffer, 0, len);
		}
	}

	public static byte[] streamToByteArray(InputStream in) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		copyStream(in, baos);
		return baos.toByteArray();
	}

	public static String md5Sum(byte[] data) {
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			byte[] digest = md.digest(data);
			try (Formatter formatter = new Formatter(Locale.ROOT)) {
				for (byte b : digest) {
					formatter.format("%02x", b);
				}
				return formatter.toString();
			}
		} catch (NoSuchAlgorithmException e) {
			throw new JadxRuntimeException("MD5 algorithm not available", e);
		}
	}

	public static String md5Sum(File file) {
		try (InputStream is = new FileInputStream(file)) {
			MessageDigest md = MessageDigest.getInstance("MD5");
			try (DigestInputStream dis = new DigestInputStream(is, md)) {
				byte[] buffer = new byte[READ_BUFFER_SIZE];
				// read stream to EOF as normal...
				// noinspection StatementWithEmptyBody
				while (dis.read(buffer) != -1) {
					// just read
				}
			}
			byte[] digest = md.digest();
			try (Formatter formatter = new Formatter(Locale.ROOT)) {
				for (byte b : digest) {
					formatter.format("%02x", b);
				}
				return formatter.toString();
			}
		} catch (IOException | NoSuchAlgorithmException e) {
			throw new JadxRuntimeException("Failed to calculate md5 for file: " + file, e);
		}
	}

	public static List<Path> expandDirs(List<Path> input) {
		List<Path> files = new ArrayList<>();
		for (Path path : input) {
			if (Files.isDirectory(path)) {
				expandDir(path, files);
			} else {
				files.add(path);
			}
		}
		return files;
	}

	private static void expandDir(Path dir, List<Path> files) {
		try (Stream<Path> walk = Files.walk(dir, FileVisitOption.FOLLOW_LINKS)) {
			walk.filter(Files::isRegularFile).forEach(files::add);
		} catch (Exception e) {
			LOG.error("Failed to list files in directory: {}", dir, e);
		}
	}

	public static void addFileToJar(JarOutputStream jar, File source, String entryName) throws IOException {
		try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source))) {
			JarEntry entry = new JarEntry(entryName);
			entry.setTime(source.lastModified());
			jar.putNextEntry(entry);

			copyStream(in, jar);
			jar.closeEntry();
		}
	}

	public static void makeDirsForFile(Path path) {
		if (path != null) {
			Path parent = path.toAbsolutePath().getParent();
			if (parent != null) {
				makeDirs(parent.toFile());
			}
		}
	}

	public static void makeDirsForFile(File file) {
		if (file != null) {
			makeDirs(file.getParentFile());
		}
	}

	private static final Object MKDIR_SYNC = new Object();

	public static void makeDirs(@Nullable File dir) {
		if (dir != null) {
			synchronized (MKDIR_SYNC) {
				if (!dir.mkdirs() && !dir.isDirectory()) {
					throw new JadxRuntimeException("Can't create directory " + dir);
				}
			}
		}
	}

	public static void makeDirs(@Nullable Path dir) {
		if (dir != null) {
			makeDirs(dir.toFile());
		}
	}

	public static Path createTempFile(String suffix) {
		try {
			Path temp = Files.createTempFile("jadx-tmp-", suffix);
			temp.toFile().deleteOnExit();
			return temp;
		} catch (IOException e) {
			throw new JadxRuntimeException("Failed to create temp file", e);
		}
	}

	public static boolean isZipFile(File file) {
		if (!file.isFile()) {
			return false;
		}
		try (ZipFile zipFile = new ZipFile(file)) {
			return zipFile.size() > 0;
		} catch (IOException e) {
			return false;
		}
	}

	public static String getPathBaseName(Path path) {
		Path fileName = path.getFileName();
		if (fileName == null) {
			return "";
		}
		String name = fileName.toString();
		int dot = name.lastIndexOf('.');
		return dot == -1 ? name : name.substring(0, dot);
	}

	public static File prepareFile(File file) {
		File parent = file.getParentFile();
		if (parent != null && !parent.exists()) {
			makeDirs(parent);
		}
		return file;
	}

	public static void deleteFileIfExists(Path filePath) throws IOException {
		Files.deleteIfExists(filePath);
	}

	public static boolean deleteDir(File dir) {
		deleteDir(dir.toPath());
		return true;
	}

	public static void deleteDirIfExists(Path dir) {
		if (Files.exists(dir)) {
			try {
				deleteDir(dir);
			} catch (Exception e) {
				LOG.error("Failed to delete dir: " + dir.toAbsolutePath(), e);
			}
		}
	}

	public static void deleteDir(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted((p1, p2) -> p2.compareTo(p1)) // delete children first
					.forEach(path -> {
						try {
							Files.delete(path);
						} catch (IOException e) {
							LOG.error("Failed to delete path: {}", path, e);
						}
					});
		} catch (IOException e) {
			LOG.error("Failed to walk dir: {}", dir, e);
		}
	}

	public static void copyFile(Path src, Path dest) {
		try {
			makeDirsForFile(dest);
			Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new JadxRuntimeException("Failed to copy file from " + src + " to " + dest, e);
		}
	}

	public static List<File> listFiles(File dir, @Nullable FilenameFilter filter) {
		File[] files = dir.listFiles(filter);
		if (files == null) {
			return new ArrayList<>();
		}
		List<File> list = new ArrayList<>(files.length);
		for (File f : files) {
			list.add(f);
		}
		return list;
	}

	public static List<File> listFilesRecursive(File dir, @Nullable FilenameFilter filter) {
		List<File> result = new ArrayList<>();
		listFilesRecursive(dir, filter, result);
		return result;
	}

	private static void listFilesRecursive(File dir, @Nullable FilenameFilter filter, List<File> result) {
		File[] files = dir.listFiles();
		if (files == null) {
			return;
		}
		for (File f : files) {
			if (f.isDirectory()) {
				listFilesRecursive(f, filter, result);
			} else if (filter == null || filter.accept(dir, f.getName())) {
				result.add(f);
			}
		}
	}

	public static void extractJar(File jarFile, File outDir) {
		try (JarFile jar = new JarFile(jarFile)) {
			Enumeration<JarEntry> entries = jar.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				File outFile = new File(outDir, entry.getName());
				if (entry.isDirectory()) {
					makeDirs(outFile);
				} else {
					makeDirsForFile(outFile);
					try (InputStream in = jar.getInputStream(entry);
							OutputStream out = new FileOutputStream(outFile)) {
						copyStream(in, out);
					}
				}
			}
		} catch (IOException e) {
			throw new JadxRuntimeException("Failed to extract jar: " + jarFile, e);
		}
	}

	public static void copyResourceToFile(String resourcePath, File outFile) {
		try (InputStream in = Objects.requireNonNull(
				FileUtils.class.getResourceAsStream(resourcePath),
				"Resource not found: " + resourcePath);
				OutputStream out = new FileOutputStream(outFile)) {
			copyStream(in, out);
		} catch (IOException e) {
			throw new JadxRuntimeException("Failed to copy resource " + resourcePath + " to file " + outFile, e);
		}
	}
}
