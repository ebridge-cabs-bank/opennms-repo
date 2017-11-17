package org.opennms.repo.impl.rpm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opennms.repo.api.NameRegexFilter;
import org.opennms.repo.api.Repository;
import org.opennms.repo.api.Util;
import org.opennms.repo.api.Version;
import org.opennms.repo.impl.AbstractTestCase;
import org.opennms.repo.impl.TestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RPMRepositoryTest extends AbstractTestCase {
	private static final Logger LOG = LoggerFactory.getLogger(RPMRepositoryTest.class);
	
	@BeforeClass
	public static void removeTestData() throws IOException {
		Util.recursiveDelete(s_repositoryTestRoot.resolve("RPMRepositoryTest"));
	}

	@Test
	public void testCreateEmptyRepository() throws Exception {
		final Path repositoryPath = getRepositoryPath();
		Repository repo = new RPMRepository(repositoryPath);
		assertFalse(repo.isValid());

		repo.index(getGPGInfo());
		TestUtils.assertFileExists(repositoryPath + "/repodata");
		TestUtils.assertFileExists(repositoryPath + "/repodata/repomd.xml");
		TestUtils.assertFileExists(repositoryPath + "/repodata/repomd.xml.asc");
		TestUtils.assertFileExists(repositoryPath + "/repodata/repomd.xml.key");
	}

	@Test
	public void testAddRPMsToRepository() throws Exception {
		final Path repositoryPath = getRepositoryPath();
		Repository repo = new RPMRepository(repositoryPath);
		assertFalse(repo.isValid());

		final Path outputPath = repositoryPath.resolve("rpms").resolve("jicmp").resolve("x86_64");
		final File packageA1File = new File(outputPath.toFile(), TestUtils.A1_X64_FILENAME);
		final File packageA2File = new File(outputPath.toFile(), TestUtils.A2_X64_FILENAME);
		final File packageA3File = new File(outputPath.toFile(), TestUtils.A3_X64_FILENAME);

		repo.addPackages(
				RPMUtils.getPackage(TestUtils.A1_X64_PATH.toFile()),
				RPMUtils.getPackage(TestUtils.A2_X64_PATH.toFile()),
				RPMUtils.getPackage(TestUtils.A3_X64_PATH.toFile())
				);
		repo.index(getGPGInfo());

		final RPMPackage packageA1 = RPMUtils.getPackage(packageA1File);
		final RPMPackage packageA2 = RPMUtils.getPackage(packageA2File);
		final RPMPackage packageA3 = RPMUtils.getPackage(packageA3File);

		TestUtils.assertFileExists(repositoryPath.toAbsolutePath() + "/drpms/" + new DeltaRPM(packageA1, packageA3).getFileName());
		TestUtils.assertFileExists(repositoryPath.toAbsolutePath() + "/drpms/" + new DeltaRPM(packageA2, packageA3).getFileName());
		TestUtils.assertFileExists(packageA1File.getAbsolutePath());
		TestUtils.assertFileExists(packageA2File.getAbsolutePath());
		TestUtils.assertFileExists(packageA3File.getAbsolutePath());
	}

	@Test
	public void testCreateRepositoryWithRPMs() throws Exception {
		final Path repositoryPath = getRepositoryPath();
		Repository repo = new RPMRepository(repositoryPath);
		assertFalse(repo.isValid());

		final Path rpmPath = repositoryPath.resolve("rpms").resolve("jicmp").resolve("x86_64");
		final File rpmDir = rpmPath.toFile();
		Files.createDirectories(rpmDir.toPath());
		final File packageA1File = new File(rpmDir, TestUtils.A1_X64_FILENAME);
		final File packageA2File = new File(rpmDir, TestUtils.A2_X64_FILENAME);
		final File packageA3File = new File(rpmDir, TestUtils.A3_X64_FILENAME);

		FileUtils.copyFileToDirectory(TestUtils.A1_X64_PATH.toFile(), rpmDir);
		FileUtils.copyFileToDirectory(TestUtils.A2_X64_PATH.toFile(), rpmDir);
		FileUtils.copyFileToDirectory(TestUtils.A3_X64_PATH.toFile(), rpmDir);

		TestUtils.assertFileExists(packageA1File.getPath());
		TestUtils.assertFileExists(packageA2File.getPath());
		TestUtils.assertFileExists(packageA3File.getPath());

		repo.index(getGPGInfo());

		final RPMPackage packageA1 = RPMUtils.getPackage(packageA1File);
		final RPMPackage packageA2 = RPMUtils.getPackage(packageA2File);
		final RPMPackage packageA3 = RPMUtils.getPackage(packageA3File);

		TestUtils.assertFileExists(repositoryPath + "/repodata");
		TestUtils.assertFileExists(repositoryPath + "/repodata/repomd.xml");
		TestUtils.assertFileExists(repositoryPath + "/repodata/repomd.xml.asc");
		TestUtils.assertFileExists(repositoryPath + "/repodata/repomd.xml.key");
		TestUtils.assertFileExists(repositoryPath + "/drpms/" + new DeltaRPM(packageA1, packageA3).getFileName());
		TestUtils.assertFileExists(repositoryPath + "/drpms/" + new DeltaRPM(packageA2, packageA3).getFileName());

		final List<String> lines = new ArrayList<>();
		Files.walk(repositoryPath.resolve("repodata")).forEach(path -> {
			if (path.toString().contains("-filelists.xml")) {
				try (final FileInputStream fis = new FileInputStream(path.toFile());
						final GZIPInputStream gis = new GZIPInputStream(fis);
						final InputStreamReader isr = new InputStreamReader(gis)) {
					lines.addAll(IOUtils.readLines(gis, Charset.defaultCharset()));
				} catch (final IOException e) {
					LOG.debug("faild to read from {}", path, e);
				}
				;
			}
		});

		final Pattern packagesPattern = Pattern.compile(".*packages=\"(\\d+)\".*");
		final Pattern versionPattern = Pattern.compile("\\s*<version epoch=\"(\\d+)\" ver=\"([^\"]*)\" rel=\"([^\\\"]*)\"/>\\s*");
		assertTrue("There should be data in *-filelists.xml.gz", lines.size() > 0);
		int packages = 0;
		final Set<Version> versions = new TreeSet<>();
		for (final String line : lines) {
			final Matcher packagesMatcher = packagesPattern.matcher(line);
			final Matcher versionMatcher = versionPattern.matcher(line);
			if (packagesMatcher.matches()) {
				packages = Integer.valueOf(packagesMatcher.group(1));
			} else if (versionMatcher.matches()) {
				final int epoch = Integer.valueOf(versionMatcher.group(1)).intValue();
				final String version = versionMatcher.group(2);
				final String release = versionMatcher.group(3);
				versions.add(new RPMVersion(epoch, version, release));
			} else {
				// LOG.debug("Does not match: {}", line);
			}
		}

		assertEquals("There should be 3 packages in the file list.", 3, packages);
		final Iterator<Version> it = versions.iterator();

		assertTrue(it.hasNext());
		Version v = it.next();
		assertEquals(new RPMVersion(0, "1.4.1", "1"), v);

		assertTrue(it.hasNext());
		v = it.next();
		assertEquals(new RPMVersion(0, "1.4.5", "2"), v);

		assertTrue(it.hasNext());
		v = it.next();
		assertEquals(new RPMVersion(0, "2.0.0", "0.1"), v);
	}

	@Test
	public void testCreateRepositoryNoUpdates() throws Exception {
		final Path repositoryPath = getRepositoryPath();
		Repository repo = new RPMRepository(repositoryPath);
		assertFalse(repo.isValid());

		final Path rpmPath = repositoryPath.resolve("rpms").resolve("jicmp").resolve("x86_64");
		Files.createDirectories(rpmPath);
		final File packageA1File = rpmPath.resolve(TestUtils.A1_X64_FILENAME).toFile();
		final File packageA2File = rpmPath.resolve(TestUtils.A2_X64_FILENAME).toFile();
		final File packageA3File = rpmPath.resolve(TestUtils.A3_X64_FILENAME).toFile();

		FileUtils.copyFileToDirectory(TestUtils.A1_X64_PATH.toFile(), rpmPath.toFile());
		FileUtils.copyFileToDirectory(TestUtils.A2_X64_PATH.toFile(), rpmPath.toFile());
		FileUtils.copyFileToDirectory(TestUtils.A3_X64_PATH.toFile(), rpmPath.toFile());

		TestUtils.assertFileExists(packageA1File.getPath());
		TestUtils.assertFileExists(packageA2File.getPath());
		TestUtils.assertFileExists(packageA3File.getPath());

		repo.index(getGPGInfo());

		final RPMPackage packageA1 = RPMUtils.getPackage(packageA1File);
		final RPMPackage packageA2 = RPMUtils.getPackage(packageA2File);
		final RPMPackage packageA3 = RPMUtils.getPackage(packageA3File);

		final DeltaRPM deltaA13 = new DeltaRPM(packageA1, packageA3);
		final DeltaRPM deltaA23 = new DeltaRPM(packageA2, packageA3);

		TestUtils.assertFileExists(repositoryPath + "/repodata/repomd.xml");
		TestUtils.assertFileExists(repositoryPath + "/repodata/repomd.xml.asc");
		TestUtils.assertFileExists(repositoryPath + "/repodata/repomd.xml.key");
		TestUtils.assertFileExists(repositoryPath + "/drpms/" + deltaA13.getFileName());
		TestUtils.assertFileExists(repositoryPath + "/drpms/" + deltaA23.getFileName());

		final Path repodata = repositoryPath.resolve("repodata");
		final Path drpms = repositoryPath.resolve("drpms");

		final Map<Path, FileTime> fileTimes = new HashMap<>();
		final Path[] repositoryPaths = new Path[] {
				repodata.resolve("repomd.xml"),
				repodata.resolve("repomd.xml.asc"),
				repodata.resolve("repomd.xml.key"),
				deltaA13.getFilePath(drpms),
				deltaA23.getFilePath(drpms)
		};

		for (final Path p : repositoryPaths) {
			fileTimes.put(p, Util.getFileTime(p));
		}

		repo = new RPMRepository(repositoryPath);
		repo.index(getGPGInfo());

		for (final Path p : repositoryPaths) {
			assertEquals(p + " time should not have changed after a reindex", fileTimes.get(p).toMillis(), Util.getFileTime(p).toMillis());
		}
	}

	@Test
	public void testAddPackages() throws Exception {
		final Path sourceRepositoryPath = getRepositoryPath().resolve("source");
		final Path targetRepositoryPath = getRepositoryPath().resolve("target");
		Repository sourceRepo = new RPMRepository(sourceRepositoryPath);
		Repository targetRepo = new RPMRepository(targetRepositoryPath);

		final Path sourceRpmPath = sourceRepositoryPath.resolve("rpms").resolve("jicmp").resolve("x86_64");
		final Path targetRpmPath = targetRepositoryPath.resolve("rpms").resolve("jicmp").resolve("x86_64");
		Files.createDirectories(sourceRpmPath);
		Files.createDirectories(targetRpmPath);

		final Path packageA2SourcePath = sourceRpmPath.resolve(TestUtils.A2_X64_FILENAME);
		final Path packageA1TargetPath = targetRpmPath.resolve(TestUtils.A1_X64_FILENAME);
		final Path packageA2TargetPath = targetRpmPath.resolve(TestUtils.A2_X64_FILENAME);

		FileUtils.copyFileToDirectory(TestUtils.A1_X64_PATH.toFile(), targetRpmPath.toFile());
		FileUtils.copyFileToDirectory(TestUtils.A2_X64_PATH.toFile(), sourceRpmPath.toFile());

		TestUtils.assertFileExists(packageA1TargetPath);
		TestUtils.assertFileExists(packageA2SourcePath);
		TestUtils.assertFileDoesNotExist(packageA2TargetPath);

		targetRepo.addPackages(sourceRepo);
		TestUtils.assertFileExists(packageA2TargetPath);
	}

	@Test
	public void testAddPackagesWithFilters() throws Exception {
		final Path sourceRepositoryPath = getRepositoryPath().resolve("source");
		final Path targetRepositoryPath = getRepositoryPath().resolve("target");
		Repository sourceRepo = new RPMRepository(sourceRepositoryPath);
		Repository targetRepo = new RPMRepository(targetRepositoryPath);

		final Path sourceRpmPath = sourceRepositoryPath.resolve("rpms").resolve("x86_64");
		Files.createDirectories(sourceRpmPath);
		Files.createDirectories(targetRepo.getRoot());

		final Path packageA1SourcePath = sourceRpmPath.resolve(TestUtils.A1_X64_FILENAME);
		final Path packageB1SourcePath = sourceRpmPath.resolve(TestUtils.B1_X64_FILENAME);
		final Path packageA1TargetPath = targetRepositoryPath.resolve("rpms").resolve("jicmp").resolve("x86_64").resolve(TestUtils.A1_X64_FILENAME);
		final Path packageB1TargetPath = targetRepositoryPath.resolve("rpms").resolve("jicmp6").resolve("x86_64").resolve(TestUtils.B1_X64_FILENAME);

		FileUtils.copyFileToDirectory(TestUtils.A1_X64_PATH.toFile(), sourceRpmPath.toFile());
		FileUtils.copyFileToDirectory(TestUtils.B1_X64_PATH.toFile(), sourceRpmPath.toFile());

		TestUtils.assertFileExists(packageA1SourcePath);
		TestUtils.assertFileExists(packageB1SourcePath);
		TestUtils.assertFileDoesNotExist(packageA1TargetPath);
		TestUtils.assertFileDoesNotExist(packageB1TargetPath);

		targetRepo.addPackages(sourceRepo, new NameRegexFilter("^jicmp$"));
		TestUtils.assertFileExists(packageA1TargetPath);
		TestUtils.assertFileDoesNotExist(packageB1TargetPath);

		targetRepo.index(getGPGInfo());
		assertEquals(1, targetRepo.getPackages().size());
		assertEquals("jicmp", targetRepo.getPackages().iterator().next().getName());
	}

	@Test
	public void testAddOldPackages() throws Exception {
		final Path sourceRepositoryPath = getRepositoryPath().resolve("source");
		final Path targetRepositoryPath = getRepositoryPath().resolve("target");
		Repository sourceRepo = new RPMRepository(sourceRepositoryPath);
		Repository targetRepo = new RPMRepository(targetRepositoryPath);

		final File sourceRepositoryDir = sourceRepositoryPath.toFile();
		final File targetRepositoryDir = targetRepositoryPath.toFile();
		Files.createDirectories(sourceRepositoryPath);
		Files.createDirectories(targetRepositoryPath);
		final File packageASourceFile = new File(sourceRepositoryDir, TestUtils.A1_X64_FILENAME);
		final File packageATargetFile = new File(targetRepositoryDir, TestUtils.A2_X64_FILENAME);
		final String packageA1TargetFile = targetRepositoryDir + File.separator + TestUtils.A1_X64_FILENAME;

		FileUtils.copyFileToDirectory(TestUtils.A1_X64_PATH.toFile(), sourceRepositoryDir);
		FileUtils.copyFileToDirectory(TestUtils.A2_X64_PATH.toFile(), targetRepositoryDir);

		TestUtils.assertFileExists(packageASourceFile.getAbsolutePath());
		TestUtils.assertFileExists(packageATargetFile.getAbsolutePath());
		TestUtils.assertFileDoesNotExist(packageA1TargetFile);

		targetRepo.addPackages(sourceRepo);
		TestUtils.assertFileDoesNotExist(packageA1TargetFile);
	}

	@Test
	public void testInheritedRepository() throws Exception {
		final Path sourceRepositoryPath = getRepositoryPath().resolve("source");
		final Path targetRepositoryPath = getRepositoryPath().resolve("target");

		final File sourceRepositoryDir = sourceRepositoryPath.toFile();
		final File targetRepositoryDir = targetRepositoryPath.toFile();
		Files.createDirectories(sourceRepositoryPath);
		Files.createDirectories(targetRepositoryPath);

		FileUtils.copyFileToDirectory(TestUtils.A1_X64_PATH.toFile(), new File(targetRepositoryDir, "x86_64"));
		FileUtils.copyFileToDirectory(TestUtils.A2_X64_PATH.toFile(), new File(sourceRepositoryDir, "x86_64"));

		Repository sourceRepo = new RPMRepository(sourceRepositoryPath);
		Repository targetRepo = new RPMRepository(targetRepositoryPath, Util.newSortedSet(sourceRepo));

		final Path packageA2TargetPath = targetRepositoryPath.resolve("rpms").resolve("jicmp").resolve("x86_64").resolve(TestUtils.A2_X64_FILENAME);
		TestUtils.assertFileDoesNotExist(packageA2TargetPath);

		targetRepo.index(getGPGInfo());
		TestUtils.assertFileExists(packageA2TargetPath);
	}

	@Test
	public void testInheritedRepositoryMultipleParents() throws Exception {
		final Path sourceRepositoryAPath = getRepositoryPath().resolve("sourceA");
		final Path sourceRepositoryBPath = getRepositoryPath().resolve("sourceB");
		final Path targetRepositoryPath = getRepositoryPath().resolve("target");

		final File sourceRepositoryADir = sourceRepositoryAPath.toFile();
		final File sourceRepositoryBDir = sourceRepositoryBPath.toFile();
		final File targetRepositoryDir = targetRepositoryPath.toFile();
		Files.createDirectories(sourceRepositoryAPath);
		Files.createDirectories(sourceRepositoryBPath);
		Files.createDirectories(targetRepositoryPath);

		FileUtils.copyFileToDirectory(TestUtils.A1_X64_PATH.toFile(), new File(sourceRepositoryADir, "x86_64"));
		FileUtils.copyFileToDirectory(TestUtils.A2_X64_PATH.toFile(), new File(sourceRepositoryBDir, "x86_64"));
		FileUtils.copyFileToDirectory(TestUtils.A3_X64_PATH.toFile(), new File(targetRepositoryDir, "x86_64"));

		Repository sourceRepoA = new RPMRepository(sourceRepositoryAPath);
		Repository sourceRepoB = new RPMRepository(sourceRepositoryBPath);
		Repository targetRepo = new RPMRepository(targetRepositoryPath, Util.newSortedSet(sourceRepoA, sourceRepoB));

		// indexing should not have copied any RPMs, but it *should* have
		// created DRPMs
		targetRepo.index(getGPGInfo());

		final Path packageA1TargetPath = targetRepositoryPath.resolve("x86_64").resolve(TestUtils.A1_X64_FILENAME);
		TestUtils.assertFileDoesNotExist(packageA1TargetPath);

		final Path packageA2TargetPath = targetRepositoryPath.resolve("x86_64").resolve(TestUtils.A2_X64_FILENAME);
		TestUtils.assertFileDoesNotExist(packageA2TargetPath);

		final Path drpmPath = targetRepositoryPath.resolve("drpms");

		final DeltaRPM drpmA1A3 = new DeltaRPM(RPMUtils.getPackage(TestUtils.A1_X64_PATH), RPMUtils.getPackage(TestUtils.A3_X64_PATH));
		TestUtils.assertFileExists(drpmA1A3.getFilePath(drpmPath));

		final DeltaRPM drpmA2A3 = new DeltaRPM(RPMUtils.getPackage(TestUtils.A2_X64_PATH), RPMUtils.getPackage(TestUtils.A3_X64_PATH));
		TestUtils.assertFileExists(drpmA2A3.getFilePath(drpmPath));
	}

	@Test
	public void testRemoveOldDRPMS() throws Exception {
		final Path repositoryPath = getRepositoryPath();
		Files.createDirectories(repositoryPath.resolve("drpms"));
		FileUtils.copyFileToDirectory(TestUtils.A4_X64_PATH.toFile(), repositoryPath.toFile());

		final File a1File = TestUtils.A1_X64_PATH.toFile();
		final File a2File = TestUtils.A2_X64_PATH.toFile();
		final File a3File = TestUtils.A3_X64_PATH.toFile();
		final File a4File = TestUtils.A4_X64_PATH.toFile();

		final String a1a2delta = RPMUtils.getDeltaFileName(a1File, a2File);
		final String a2a3delta = RPMUtils.getDeltaFileName(a2File, a3File);
		final String a3a4delta = RPMUtils.getDeltaFileName(a3File, a4File);

		final Path drpmPath = repositoryPath.resolve("drpms");

		RPMUtils.generateDelta(a1File, a2File, drpmPath.resolve(a1a2delta).toFile());
		RPMUtils.generateDelta(a2File, a3File, drpmPath.resolve(a2a3delta).toFile());
		RPMUtils.generateDelta(a3File, a4File, drpmPath.resolve(a3a4delta).toFile());

		final RPMRepository repo = new RPMRepository(repositoryPath);
		repo.index(getGPGInfo());

		TestUtils.assertFileDoesNotExist(drpmPath.resolve(a1a2delta));
		TestUtils.assertFileDoesNotExist(drpmPath.resolve(a2a3delta));
		TestUtils.assertFileExists(drpmPath.resolve(a3a4delta));
	}

	@Test
	public void testNormalize() throws Exception {
		final Path repositoryPath = getRepositoryPath();
		Files.createDirectories(repositoryPath);

		FileUtils.copyFileToDirectory(TestUtils.A1_X64_PATH.toFile(), repositoryPath.toFile());
		FileUtils.copyFileToDirectory(TestUtils.A2_X64_PATH.toFile(), repositoryPath.toFile());
		FileUtils.copyFileToDirectory(TestUtils.A3_X64_PATH.toFile(), repositoryPath.toFile());

		final Repository repo = new RPMRepository(repositoryPath);

		final Path archPath = repositoryPath.resolve("rpms").resolve("jicmp").resolve("x86_64");
		final Path drpmPath = repositoryPath.resolve("drpms");

		final Path a1path = archPath.resolve(TestUtils.A1_X64_FILENAME);
		final Path a2path = archPath.resolve(TestUtils.A2_X64_FILENAME);
		final Path a3path = archPath.resolve(TestUtils.A3_X64_FILENAME);
		final Path drpm12path = drpmPath.resolve(RPMUtils.getDeltaFileName(TestUtils.A1_X64_PATH.toFile(), TestUtils.A2_X64_PATH.toFile()));
		final Path drpm13path = drpmPath.resolve(RPMUtils.getDeltaFileName(TestUtils.A1_X64_PATH.toFile(), TestUtils.A3_X64_PATH.toFile()));
		final Path drpm23path = drpmPath.resolve(RPMUtils.getDeltaFileName(TestUtils.A2_X64_PATH.toFile(), TestUtils.A3_X64_PATH.toFile()));

		TestUtils.assertFileDoesNotExist(a1path);
		TestUtils.assertFileDoesNotExist(a2path);
		TestUtils.assertFileDoesNotExist(a3path);
		TestUtils.assertFileDoesNotExist(drpm12path);
		TestUtils.assertFileDoesNotExist(drpm13path);
		TestUtils.assertFileDoesNotExist(drpm23path);

		repo.normalize();

		TestUtils.assertFileExists(a1path);
		TestUtils.assertFileExists(a2path);
		TestUtils.assertFileExists(a3path);
		TestUtils.assertFileDoesNotExist(drpm12path);
		TestUtils.assertFileDoesNotExist(drpm13path);
		TestUtils.assertFileDoesNotExist(drpm23path);

		repo.index(getGPGInfo());

		TestUtils.assertFileDoesNotExist(drpm12path);
		TestUtils.assertFileExists(drpm13path);
		TestUtils.assertFileExists(drpm23path);
	}

	@Test
	public void testClone() throws Exception {
		final Path sourceRepositoryPath = getRepositoryPath().resolve("source");
		final Path targetRepositoryPath = getRepositoryPath().resolve("target");
		final File sourceRepositoryDir = sourceRepositoryPath.toFile();
		final File targetRepositoryDir = targetRepositoryPath.toFile();
		Files.createDirectories(sourceRepositoryPath);
		Files.createDirectories(targetRepositoryPath);

		FileUtils.copyFileToDirectory(TestUtils.A1_X64_PATH.toFile(), targetRepositoryDir);
		FileUtils.copyFileToDirectory(TestUtils.A2_X64_PATH.toFile(), sourceRepositoryDir);
		FileUtils.copyFileToDirectory(TestUtils.A3_X64_PATH.toFile(), sourceRepositoryDir);

		Repository sourceRepo = new RPMRepository(sourceRepositoryPath);
		Repository targetRepo = new RPMRepository(targetRepositoryPath);
		sourceRepo.index(getGPGInfo());
		targetRepo.index(getGPGInfo());

		final String packageA1TargetPath = targetRepositoryDir + File.separator + TestUtils.A1_X64_FILENAME;
		final String packageA2TargetPath = targetRepositoryDir + File.separator + TestUtils.A2_X64_FILENAME;
		final String packageA3TargetPath = targetRepositoryDir + File.separator + TestUtils.A3_X64_FILENAME;
		TestUtils.assertFileExists(packageA1TargetPath);
		TestUtils.assertFileDoesNotExist(packageA2TargetPath);
		TestUtils.assertFileDoesNotExist(packageA3TargetPath);

		sourceRepo.cloneInto(targetRepositoryPath);
		targetRepo.index(getGPGInfo());

		TestUtils.assertFileDoesNotExist(packageA1TargetPath);
		TestUtils.assertFileExists(packageA2TargetPath);
		TestUtils.assertFileExists(packageA3TargetPath);
	}
}