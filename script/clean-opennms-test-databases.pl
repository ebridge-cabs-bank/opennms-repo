#!/usr/bin/perl -w

use strict;
use warnings;

use Config qw();
use IO::Handle;
use version;

use OpenNMS::Release;

use vars qw(
	$PSQL
	$SUDO
);

print $0 . ' ' . version->new($OpenNMS::Release::VERSION) . "\n";

chomp($SUDO = `which sudo 2>/dev/null`);
if (not defined $SUDO or $SUDO eq '' or ! -x $SUDO) {
	die "Unable to locate sudo!\n";
}

$ENV{'PATH'} = $ENV{'PATH'} . $Config::Config{path_sep} . '/usr/sbin' . $Config::Config{path_sep} . '/sbin';

if (-x '/sw/bin/pgsql.sh') {
	print STDOUT "- resetting PostgreSQL:\n";
	system($SUDO, '/sw/bin/pgsql.sh', 'stop');
	sleep(5);
	system($SUDO, '/sw/bin/pgsql.sh', 'start');
	sleep(5);
	$PSQL = '/sw/bin/psql';
}

if (-x '/etc/init.d/postgresql') {
	print STDOUT "- resetting PostgreSQL:\n";
	system($SUDO, '/etc/init.d/postgresql', 'restart');
	sleep(5);
	$PSQL = '/usr/bin/psql';
}

if (not defined $PSQL) {
	print STDERR "Not sure what system this is, going to skip messing with PostgreSQL.\n";
	exit(0);
}

my $handle = IO::Handle->new();

my @databases = qw();

open($handle, '-|', "$PSQL -U opennms -l -t template1") or die "Unable to run $PSQL: $!\n";
while (my $line = <$handle>) {
	if ($line =~ /^\s*(opennms_test_.*?)\s*\|/) {
		push(@databases, $1);
	}
}
close($handle);

DATABASE: for my $database (@databases) {
	print STDOUT "- deleting $database... ";
	for my $user ('opennms', 'postgres') {
		if (system($PSQL, '-U', $user, '-c', "DROP DATABASE $database;", 'template1') == 0) {
			next DATABASE;
		}
	}
	die "Failed to drop $database: $!\n";
}

exit(0);
