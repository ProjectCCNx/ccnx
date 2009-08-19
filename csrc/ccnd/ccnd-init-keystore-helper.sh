#!/bin/sh
: ${RSA_KEYSIZE:=1024}
exec >&2
Fail () {
  echo '*** Failed' "$*"
  exit 1
}
cd `dirname "$1"` || Fail bad setup
umask 077
trap 'rm -f *.pem openssl.cnf p' 0
cat <<EOF >openssl.cnf
# This is not really relevant because we're not sending cert requests anywhere,
# but openssl req can refuse to go on if it has no config file.
[ req ]
distinguished_name	= req_distinguished_name
[ req_distinguished_name ]
countryName			= Country Name (2 letter code)
countryName_default		= AU
countryName_min			= 2
countryName_max			= 2
EOF
openssl req -config openssl.cnf       \
            -newkey rsa:$RSA_KEYSIZE  \
            -x509                     \
            -keyout private_key.pem   \
            -out certout.pem          \
            -subj /CN="CCND-internal" \
            -nodes                   || Fail openssl req
openssl pkcs12 -export                \
            -name "CCND"              \
            -out "$1"                 \
            -in certout.pem           \
            -inkey private_key.pem    \
            -password file:p         || Fail openssl pkcs12
