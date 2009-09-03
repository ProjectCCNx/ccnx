cat <<EOF
CCN Technical Documentation Index
=================================

== Technical Documentation
EOF
grep '<title>.*</title>' *.html | sed -e 's/^/ - link:/' -e 's/:[ 	]*<title>/\[/' -e 's/<.title>/\]/'
cat <<EOF

link:../index.html[UP]
EOF
