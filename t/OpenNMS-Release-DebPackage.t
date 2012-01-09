$|++;

use Test::More;
BEGIN {
	my $dpkg = `which dpkg 2>/dev/null`;
	if ($? == 0) {
		plan tests => 22;
		use_ok('OpenNMS::Release::DebPackage');
	} else {
		plan skip_all => '`dpkg` not found, skipping Debian tests.';
	}
};

my ($deb);

# t/dists/opennms-1.8/main/binary-i386/opennms_1.8.16-1_all.deb
# t/dists/nightly-1.11/main/binary-i386/opennms_1.11.0-0.20111216.14_all.deb

$deb = OpenNMS::Release::DebPackage->new();
is($deb, undef, "Check for invalid deb when no path is provided.");

$deb = OpenNMS::Release::DebPackage->new("t/dists/nightly-1.11/main/binary-i386/opennms_1.11.0-0.20111216.14_all.deb");
isa_ok($deb, 'OpenNMS::Release::DebPackage');

is($deb->name,             'opennms',       'Package name is "opennms".');
is($deb->version->epoch,   undef,           'Epoch should be undefined.');
is($deb->version->version, '1.11.0',        'Version should be 1.11.0.');
is($deb->version->release, '0.20111216.14', 'Release should be snapshot.');
is($deb->arch,             'all',           'Architecture should be "all".');

ok($deb->is_in_repo('t'), 'Debian package should be in t/.');
ok($deb->is_in_repo('t/../t'), 'is_in_path should handle relative paths');

$olderdeb = OpenNMS::Release::DebPackage->new("t/dists/opennms-1.8/main/binary-i386/opennms_1.8.16-1_all.deb");

is($deb->compare_to($olderdeb), 1);
is($olderdeb->compare_to($deb), -1);
ok($deb->is_newer_than($olderdeb));
ok(!$deb->is_older_than($olderdeb));
ok($olderdeb->is_older_than($deb));
ok(!$olderdeb->is_newer_than($deb));
ok($deb->equals($deb));
ok(!$deb->equals($olderdeb));


$olderdeb->copy("t/test.deb");
ok(-e 't/test.deb');
unlink "t/test.deb";

$olderdeb->copy("t");
ok(-e 't/opennms_1.8.16-1_all.deb');
unlink "t/opennms_1.8.16-1_all.deb";


$deb->symlink("t/test2.deb");
ok(-l "t/test2.deb");
unlink "t/test2.deb";

$deb->symlink("t");
ok(-l "t/opennms_1.11.0-0.20111216.14_all.deb");
unlink("t/opennms_1.11.0-0.20111216.14_all.deb");