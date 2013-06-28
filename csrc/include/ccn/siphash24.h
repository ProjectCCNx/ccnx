/* This code is based on, and distributed under the same license as:
 SipHash reference C implementation
 
 Written in 2012 by 
 Jean-Philippe Aumasson <jeanphilippe.aumasson@gmail.com>
 Daniel J. Bernstein <djb@cr.yp.to>
 
 To the extent possible under law, the author(s) have dedicated all copyright
 and related and neighboring rights to this software to the public domain
 worldwide. This software is distributed without any warranty.
 
 You should have received a copy of the CC0 Public Domain Dedication along with
 this software. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 
 */
uint64_t siphash_2_4(const unsigned char *in, size_t inlen, const unsigned char *k);
