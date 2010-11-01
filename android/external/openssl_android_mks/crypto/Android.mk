# Copyright (C) 2010 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

#	LOCAL_SRC_FILES:= aes/aes_core.c

########
# These are the LIBSRC lines out of the subdirectory Makefiles
#
AES_SRC = \
	aes_core.c aes_misc.c aes_ecb.c aes_cbc.c aes_cfb.c aes_ofb.c \
	aes_ctr.c aes_ige.c aes_wrap.c
ASN1_SRC =	\
	a_object.c a_bitstr.c a_utctm.c a_gentm.c a_time.c a_int.c a_octet.c \
	a_print.c a_type.c a_set.c a_dup.c a_d2i_fp.c a_i2d_fp.c \
	a_enum.c a_utf8.c a_sign.c a_digest.c a_verify.c a_mbstr.c a_strex.c \
	x_algor.c x_val.c x_pubkey.c x_sig.c x_req.c x_attrib.c x_bignum.c \
	x_long.c x_name.c x_x509.c x_x509a.c x_crl.c x_info.c x_spki.c nsseq.c \
	x_nx509.c d2i_pu.c d2i_pr.c i2d_pu.c i2d_pr.c\
	t_req.c t_x509.c t_x509a.c t_crl.c t_pkey.c t_spki.c t_bitst.c \
	tasn_new.c tasn_fre.c tasn_enc.c tasn_dec.c tasn_utl.c tasn_typ.c \
	tasn_prn.c ameth_lib.c \
	f_int.c f_string.c n_pkey.c \
	f_enum.c x_pkey.c a_bool.c x_exten.c bio_asn1.c bio_ndef.c asn_mime.c \
	asn1_gen.c asn1_par.c asn1_lib.c asn1_err.c a_bytes.c a_strnid.c \
	evp_asn1.c asn_pack.c p5_pbe.c p5_pbev2.c p8_pkey.c asn_moid.c
BIO_SRC = \
	bio_lib.c bio_cb.c bio_err.c \
	bss_mem.c bss_null.c bss_fd.c \
	bss_file.c bss_sock.c bss_conn.c \
	bf_null.c bf_buff.c b_print.c b_dump.c \
	b_sock.c bss_acpt.c bf_nbio.c bss_log.c bss_bio.c \
	bss_dgram.c 
BN_SRC = \
	bn_add.c bn_div.c bn_exp.c bn_lib.c bn_ctx.c bn_mul.c bn_mod.c \
	bn_print.c bn_rand.c bn_shift.c bn_word.c bn_blind.c \
	bn_kron.c bn_sqrt.c bn_gcd.c bn_prime.c bn_err.c bn_sqr.c bn_asm.c \
	bn_recp.c bn_mont.c bn_mpi.c bn_exp2.c bn_gf2m.c bn_nist.c \
	bn_depr.c bn_const.c
BUFFER_SRC = buf_err.c buffer.c 
COMP_SRC = comp_lib.c comp_err.c c_rle.c c_zlib.c
CONF_SRC = conf_err.c conf_lib.c conf_api.c conf_def.c conf_mod.c \
	 conf_mall.c conf_sap.c
DES_SRC =	cbc_cksm.c cbc_enc.c  cfb64enc.c cfb_enc.c  \
	ecb3_enc.c ecb_enc.c  enc_read.c enc_writ.c \
	fcrypt.c ofb64enc.c ofb_enc.c  pcbc_enc.c \
	qud_cksm.c rand_key.c rpc_enc.c  set_key.c  \
	des_enc.c fcrypt_b.c \
	xcbc_enc.c \
	str2key.c  cfb64ede.c ofb64ede.c ede_cbcm_enc.c des_old.c des_old2.c \
	read2pwd.c
DH_SRC = dh_asn1.c dh_gen.c dh_key.c dh_lib.c dh_check.c dh_err.c dh_depr.c \
	dh_ameth.c dh_pmeth.c dh_prn.c
DSA_SRC = dsa_gen.c dsa_key.c dsa_lib.c dsa_asn1.c dsa_vrf.c dsa_sign.c \
	dsa_err.c dsa_ossl.c dsa_depr.c dsa_ameth.c dsa_pmeth.c dsa_prn.c
DSO_SRC = dso_dl.c dso_dlfcn.c dso_err.c dso_lib.c dso_null.c \
	dso_openssl.c dso_win32.c dso_vms.c dso_beos.c
EC_SRC =	ec_lib.c ecp_smpl.c ecp_mont.c ecp_nist.c ec_cvt.c ec_mult.c\
	ec_err.c ec_curve.c ec_check.c ec_print.c ec_asn1.c ec_key.c\
	ec2_smpl.c ec2_mult.c ec_ameth.c ec_pmeth.c eck_prn.c
ECDH_SRC = ech_lib.c ech_ossl.c ech_key.c ech_err.c
ECDSA_SRC = ecs_lib.c ecs_asn1.c ecs_ossl.c ecs_sign.c ecs_vrf.c ecs_err.c
ERR_SRC = err.c err_all.c err_prn.c
EVP_SRC = encode.c digest.c evp_enc.c evp_key.c evp_acnf.c \
	e_des.c e_bf.c e_idea.c e_des3.c e_camellia.c\
	e_rc4.c e_aes.c names.c e_seed.c \
	e_xcbc_d.c e_rc2.c e_cast.c e_rc5.c \
	m_null.c m_md2.c m_md4.c m_md5.c m_sha.c m_sha1.c m_wp.c \
	m_dss.c m_dss1.c m_mdc2.c m_ripemd.c m_ecdsa.c\
	p_open.c p_seal.c p_sign.c p_verify.c p_lib.c p_enc.c p_dec.c \
	bio_md.c bio_b64.c bio_enc.c evp_err.c e_null.c \
	c_all.c c_allc.c c_alld.c evp_lib.c bio_ok.c \
	evp_pkey.c evp_pbe.c p5_crpt.c p5_crpt2.c \
	e_old.c pmeth_lib.c pmeth_fn.c pmeth_gn.c m_sigver.c
HMAC_SRC = hmac.c hm_ameth.c hm_pmeth.c
MODES_SRC = cbc128.c ctr128.c cts128.c cfb128.c ofb128.c
OBJECTS_SRC = o_names.c obj_dat.c obj_lib.c obj_err.c obj_xref.c
OCSP_SRC = ocsp_asn.c ocsp_ext.c ocsp_ht.c ocsp_lib.c ocsp_cl.c \
	ocsp_srv.c ocsp_prn.c ocsp_vfy.c ocsp_err.c
PEM_SRC = pem_sign.c pem_seal.c pem_info.c pem_lib.c pem_all.c pem_err.c \
	pem_x509.c pem_xaux.c pem_oth.c pem_pk8.c pem_pkey.c pvkfmt.c
PKCS12_SRC = p12_add.c p12_asn.c p12_attr.c p12_crpt.c p12_crt.c p12_decr.c \
	p12_init.c p12_key.c p12_kiss.c p12_mutl.c\
	p12_utl.c p12_npas.c pk12err.c p12_p8d.c p12_p8e.c
PKCS7_SRC =	pk7_asn1.c pk7_lib.c pkcs7err.c pk7_doit.c pk7_smime.c pk7_attr.c \
	pk7_mime.c bio_pk7.c
RAND_SRC = md_rand.c randfile.c rand_lib.c rand_err.c rand_egd.c \
	rand_win.c rand_unix.c rand_os2.c rand_nw.c
RC2_SRC = rc2_ecb.c rc2_skey.c rc2_cbc.c rc2cfb64.c rc2ofb64.c
RC4_SRC = rc4_skey.c rc4_enc.c
RIPEMD_SRC = rmd_dgst.c rmd_one.c
RSA_SRC= rsa_eay.c rsa_gen.c rsa_lib.c rsa_sign.c rsa_saos.c rsa_err.c \
	rsa_pk1.c rsa_ssl.c rsa_none.c rsa_oaep.c rsa_chk.c rsa_null.c \
	rsa_pss.c rsa_x931.c rsa_asn1.c rsa_depr.c rsa_ameth.c rsa_prn.c \
	rsa_pmeth.c
SHA_SRC = sha_dgst.c sha1dgst.c sha_one.c sha1_one.c sha256.c sha512.c
TS_SRC = ts_err.c ts_req_utils.c ts_req_print.c ts_rsp_utils.c ts_rsp_print.c \
	ts_rsp_sign.c ts_rsp_verify.c ts_verify_ctx.c ts_lib.c ts_conf.c \
	ts_asn1.c
UI_SRC = ui_err.c ui_lib.c ui_openssl.c ui_util.c ui_compat.c
WP_SRC = wp_dgst.c wp_block.c
X509_SRC = x509_def.c x509_d2.c x509_r2x.c x509_cmp.c \
	x509_obj.c x509_req.c x509spki.c x509_vfy.c \
	x509_set.c x509cset.c x509rset.c x509_err.c \
	x509name.c x509_v3.c x509_ext.c x509_att.c \
	x509type.c x509_lu.c x_all.c x509_txt.c \
	x509_trs.c by_file.c by_dir.c x509_vpm.c
X509v3_SRC = v3_bcons.c v3_bitst.c v3_conf.c v3_extku.c v3_ia5.c v3_lib.c \
	v3_prn.c v3_utl.c v3err.c v3_genn.c v3_alt.c v3_skey.c v3_akey.c v3_pku.c \
	v3_int.c v3_enum.c v3_sxnet.c v3_cpols.c v3_crld.c v3_purp.c v3_info.c \
	v3_ocsp.c v3_akeya.c v3_pmaps.c v3_pcons.c v3_ncons.c v3_pcia.c v3_pci.c \
	pcy_cache.c pcy_node.c pcy_data.c pcy_map.c pcy_tree.c pcy_lib.c \
	v3_asid.c v3_addr.c


########
# Now use those with the right prefix
# 
CRYPTO_SRC = cryptlib.c mem.c mem_clr.c mem_dbg.c cversion.c ex_data.c cpt_err.c ebcdic.c uid.c o_time.c o_str.c o_dir.c

LOCAL_SRC_FILES += $(CRYPTO_SRC)
LOCAL_SRC_FILES += $(addprefix aes/,$(AES_SRC))
LOCAL_SRC_FILES += $(addprefix asn1/,$(ASN1_SRC))
LOCAL_SRC_FILES += $(addprefix bio/,$(BIO_SRC))
LOCAL_SRC_FILES += $(addprefix bn/,$(BN_SRC))
LOCAL_SRC_FILES += $(addprefix buffer/,$(BUFFER_SRC))
LOCAL_SRC_FILES += $(addprefix comp/,$(COMP_SRC))
LOCAL_SRC_FILES += $(addprefix conf/,$(CONF_SRC))
LOCAL_SRC_FILES += $(addprefix des/,$(DES_SRC))
LOCAL_SRC_FILES += $(addprefix dh/,$(DH_SRC))
LOCAL_SRC_FILES += $(addprefix dsa/,$(DSA_SRC))
LOCAL_SRC_FILES += $(addprefix dso/,$(DSO_SRC))
LOCAL_SRC_FILES += $(addprefix ec/,$(EC_SRC))
LOCAL_SRC_FILES += $(addprefix ecdh/,$(ECDH_SRC))
LOCAL_SRC_FILES += $(addprefix ecdsa/,$(ECDSA_SRC))
LOCAL_SRC_FILES += $(addprefix err/,$(ERR_SRC))
LOCAL_SRC_FILES += $(addprefix evp/,$(EVP_SRC))
LOCAL_SRC_FILES += $(addprefix hmac/,$(HMAC_SRC))
LOCAL_SRC_FILES += krb5/krb5_asn.c lhash/lh_stats.c lhash/lhash.c 
LOCAL_SRC_FILES += md4/md4_dgst.c md4/md4_one.c md5/md5_dgst.c md5/md5_one.c
LOCAL_SRC_FILES += $(addprefix modes/,$(MODES_SRC))
LOCAL_SRC_FILES += $(addprefix objects/,$(OBJECTS_SRC))
LOCAL_SRC_FILES += $(addprefix ocsp/,$(OCSP_SRC))
LOCAL_SRC_FILES += $(addprefix pem/,$(PEM_SRC))
LOCAL_SRC_FILES += $(addprefix pkcs12/,$(PKCS12_SRC))
LOCAL_SRC_FILES += $(addprefix pkcs7/,$(PKCS7_SRC))
LOCAL_SRC_FILES += pqueue/pqueue.c
LOCAL_SRC_FILES += $(addprefix rand/,$(RAND_SRC))
LOCAL_SRC_FILES += $(addprefix rc2/,$(RC2_SRC))
LOCAL_SRC_FILES += $(addprefix rc4/,$(RC4_SRC))
LOCAL_SRC_FILES += $(addprefix ripemd/,$(RIPEMD_SRC))
LOCAL_SRC_FILES += $(addprefix rsa/,$(RSA_SRC))
LOCAL_SRC_FILES += $(addprefix sha/,$(SHA_SRC))
LOCAL_SRC_FILES += stack/stack.c 
LOCAL_SRC_FILES += $(addprefix ts/,$(TS_SRC))
LOCAL_SRC_FILES += txt_db/txt_db.c
LOCAL_SRC_FILES += $(addprefix ui/,$(UI_SRC))
LOCAL_SRC_FILES += $(addprefix whrlpool/,$(WP_SRC))
LOCAL_SRC_FILES += $(addprefix x509/,$(X509_SRC))
LOCAL_SRC_FILES += $(addprefix x509v3/,$(X509v3_SRC))

LOCAL_CFLAGS += -DNO_WINDOWS_BRAINDEATH 

include $(LOCAL_PATH)/../android-config.mk

LOCAL_C_INCLUDES += \
	$(LOCAL_PATH)/.. \
	$(LOCAL_PATH)/asn1 \
	$(LOCAL_PATH)/evp \
	$(LOCAL_PATH)/../include 

LOCAL_LDLIBS += -ldl

LOCAL_MODULE:= libcrypto

include $(BUILD_STATIC_LIBRARY)

