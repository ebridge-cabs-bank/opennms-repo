$|++;

use File::Path;
use Data::Dumper;
use Test::More;
BEGIN {
	my $rpm = `which rpm 2>/dev/null`;
	if ($? == 0) {
		plan tests => 40;
		use_ok('OpenNMS::Release::RPM');
		use_ok('OpenNMS::Release::YumRepo');
	} else {
		plan skip_all => '`rpm` not found, skipping RPM tests.';
	}
};

rmtree("t/newrepo");

my $stable_ro = OpenNMS::Release::YumRepo->new("t/repo", "stable", "common");
isa_ok($stable_ro, 'OpenNMS::Release::YumRepo');

is($stable_ro->base, "t/repo");
is($stable_ro->release, "stable");
is($stable_ro->platform, "common");

my $stable_copy = $stable_ro->copy("t/newrepo");
ok(-d "t/newrepo");
ok(-d "t/newrepo/stable/common");
ok(-f "t/newrepo/stable/common/opennms/opennms-1.8.16-1.noarch.rpm");

$stable_copy->delete();
ok(! -d "t/newrepo/stable/common");

my ($stable_common, $stable_rhel5, $bleeding_common, $bleeding_rhel5);

reset_repos();

my $rpmlist = $stable_common->find_all_rpms();
is(scalar(@{$rpmlist}), 1);

my $rpm = $rpmlist->[0];
isa_ok($rpm, 'OpenNMS::Release::RPM');
is($rpm->name, 'opennms');

$rpmlist = $bleeding_common->find_all_rpms();
is(scalar(@{$rpmlist}), 1);

$bleeding_common->install_rpm($rpm, "opennms");
ok(-f "t/newrepo/bleeding/common/opennms/opennms-1.8.16-1.noarch.rpm");

$rpmlist = $bleeding_common->find_all_rpms();
is(scalar(@{$rpmlist}), 2);

# make sure that we don't get duplicate entries in the RPM set if we
# install an existing RPM
$bleeding_common->install_rpm($rpm, "opennms");
$rpmlist = $bleeding_common->find_all_rpms();
is(scalar(@{$rpmlist}), 2);

($rpm) = $bleeding_common->find_newest_rpm_by_name("opennms", "noarch");
is($rpm->name, "opennms");
is($rpm->version, "1.11.0");

$rpmlist = $stable_rhel5->find_all_rpms();
is(scalar(@{$rpmlist}), 1);

$rpm = $rpmlist->[0];
$bleeding_rhel5->share_rpm($stable_rhel5, $rpm);
ok(-f "t/newrepo/bleeding/rhel5/opennms/i386/iplike-2.0.2-1.i386.rpm" and not -l "t/newrepo/bleeding/rhel5/opennms/i386/iplike-2.0.2-1.i386.rpm");

$rpmlist = $bleeding_rhel5->find_newest_rpms();
is(scalar(@{$rpmlist}), 2);

$rpmlist = $bleeding_rhel5->find_all_rpms();
is(scalar(@{$rpmlist}), 3);

$bleeding_rhel5->share_rpm($stable_rhel5, $rpm);
ok(-f "t/newrepo/bleeding/rhel5/opennms/i386/iplike-2.0.2-1.i386.rpm" and not -l "t/newrepo/bleeding/rhel5/opennms/i386/iplike-2.0.2-1.i386.rpm");

$rpmlist = $bleeding_rhel5->find_all_rpms();
is(scalar(@{$rpmlist}), 3);

$rpmlist = $bleeding_rhel5->find_obsolete_rpms();

is(scalar(@{$rpmlist}), 1);
is($rpmlist->[0]->version, "1.0.7");

# subroutine says to not delete any
is($bleeding_rhel5->delete_obsolete_rpms(sub { return 0 }), 0);
ok(-e "t/newrepo/bleeding/rhel5/opennms/i386/iplike-1.0.7-1.i386.rpm");

# delete any obsolete by default
is($bleeding_rhel5->delete_obsolete_rpms(), 1);
ok(! -e "t/newrepo/bleeding/rhel5/opennms/i386/iplike-1.0.7-1.i386.rpm");

is($bleeding_common->delete_obsolete_rpms(sub { $_[0]->name ne "opennms" }), 0);

reset_repos();

$bleeding_rhel5->share_all_rpms($stable_rhel5);

$rpmlist = $bleeding_rhel5->find_all_rpms();
is(scalar(@{$rpmlist}), 3);

$rpmlist = $bleeding_rhel5->find_newest_rpms();
is(scalar(@{$rpmlist}), 2);

# this should delete the old iplike-1.0.7-1.i386
is($bleeding_rhel5->delete_obsolete_rpms(), 1);
$rpm = $bleeding_rhel5->find_newest_rpm_by_name('iplike', 'i386');
is($rpm->version, '2.0.2');
$rpm = $bleeding_rhel5->find_newest_rpm_by_name('iplike', 'x86_64');
is($rpm->version, '1.0.7');

my $copy = $bleeding_rhel5->copy("t/copy");
$rpm = OpenNMS::Release::RPM->new("t/repo/stable/common/opennms/opennms-1.8.16-1.noarch.rpm");
$copy->install_rpm($rpm, "opennms");
$bleeding_rhel5 = $copy->replace($bleeding_rhel5);
ok(! -d "t/copy");
$rpm = $bleeding_rhel5->find_newest_rpm_by_name("opennms", "noarch");
ok(defined $rpm);
is($rpm->version, "1.8.16");

$stable_common->delete;
$stable_rhel5->delete;
$bleeding_common->delete;
$bleeding_rhel5->delete;

sub reset_repos {
	rmtree("t/newrepo");
	$stable_common   = OpenNMS::Release::YumRepo->new("t/repo", "stable", "common")->copy("t/newrepo");
	$stable_rhel5    = OpenNMS::Release::YumRepo->new("t/repo", "stable", "rhel5")->copy("t/newrepo");
	$bleeding_common = OpenNMS::Release::YumRepo->new("t/repo", "bleeding", "common")->copy("t/newrepo");
	$bleeding_rhel5  = OpenNMS::Release::YumRepo->new("t/repo", "bleeding", "rhel5")->copy("t/newrepo");
}

