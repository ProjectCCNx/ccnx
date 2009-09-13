echo ""
echo "Configure and build getaddrinfo for CYGWIN"
echo ""
cd contrib
tar xzf getaddrinfo-*.tar.gz
cd getaddrinfo
./configure
make
