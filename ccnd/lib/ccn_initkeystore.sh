#!/bin/sh
# Create a ccn keystore without relying on java
: ${RSA_KEYSIZE:=1024}
: ${CCN_USER:=$USER}
Fail () {
  echo '*** Failed' "$*"
  exit 1
}
test -d .ccn && Fail .ccn already exists, $0 cowardly refusing to go on.
test $RSA_KEYSIZE -ge 512 || Fail \$RSA_KEYSIZE too small to sign CCN content
(umask 077 && mkdir .ccn) || Fail $0 Unable to create .ccn directory
cd .ccn
umask 077
trap 'rm -f *.pem' 0
openssl req -newkey rsa:$RSA_KEYSIZE -x509 -keyout private_key.pem -out certout.pem -subj /CN="$CCN_USER" -nodes || Fail openssl req
openssl pkcs12 -export -name "CCNUser" -out .ccn_keystore -in certout.pem -inkey private_key.pem \
  -password pass:'Th1s1sn0t8g00dp8ssw0rd.' || Fail openssl pkcs12
