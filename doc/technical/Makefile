# doc/technical/Makefile
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2009-2012 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

# The order here determines the order in the generated index.
DERIVED_HTML =				\
	CCNxProtocol.html		\
	Name.html			\
	URI.html			\
	ContentObject.html		\
	CryptographicAlgorithms.html	\
	InterestMessage.html		\
	CanonicalOrder.html		\
	BinaryEncoding.html		\
	NameConventions.html		\
	Timestamp.html			\
	DigestComponent.html		\
	Registration.html		\
	StatusResponse.html		\
	SignatureGeneration.html	\
	Staleness.html			\
	NameEnumerationProtocol.html	\
	RepoPolicies.html		\
	RepoProtocol.html		\
	RepositoryInfoObject.html	\
	SynchronizationProtocol.html	\
	CreateCollectionProtocol.html	\
	LinkMessages.html		\
	dtd.html			\
	xsd.html			\
	repository.dtd.html			\
	repository.xsd.html			\
	sync.dtd.html			\
	sync.xsd.html			\
	DTAG.html			\
	CCNDStatus.html			\

DERIVED_INDEX = ../index.html index.html index.txt
DEBRIS = .tagname.csv
SRC_HTML = # any checked-in html files should go here
INDEXED_HTML = $(DERIVED_HTML) $(SRC_HTML)
SCHEMA = ../../schema
XSDSRC = $(SCHEMA)/ccnx.xsd $(SCHEMA)/repository.xsd $(SCHEMA)/security.xsd
DTDSRC = $(SCHEMA)/ccnx.dtd $(SCHEMA)/repository.dtd $(SCHEMA)/security.dtd
default:
	: To build the documentation: $(MAKE) documentation

all documentation dist-docs html: $(DERIVED_HTML) $(DERIVED_INDEX)

clean-documentation: _always
	rm -f $(DERIVED_HTML) $(DERIVED_INDEX) $(DEBRIS)

clean:
	: To clean the documentation: $(MAKE) clean-documentation 
	rm -f $(DEBRIS)

DigestComponent.html: DigestComponent.txt
	asciidoc DigestComponent.txt

DTAG.html: DTAG.txt $(SCHEMA)/tagname.csvdict
	sed -e 's/$$/,,,,/g' $(SCHEMA)/tagname.csvdict | \
					cut -d , -f 1-3   > .tagname.csv
	asciidoc --unsafe DTAG.txt || : might fail if your asciidoc is too old
	rm -f .tagname.csv

dtd.html: dtd.txt $(XSDSRC)
	asciidoc --unsafe dtd.txt

xsd.html: xsd.txt $(DTDSRC)
	asciidoc --unsafe xsd.txt

repository.dtd.html: repository.dtd.txt $(XSDSRC)
	asciidoc --unsafe repository.dtd.txt

repository.xsd.html: repository.xsd.txt $(DTDSRC)
	asciidoc --unsafe repository.xsd.txt

sync.dtd.html: sync.dtd.txt $(XSDSRC)
	asciidoc --unsafe sync.dtd.txt

sync.xsd.html: sync.xsd.txt $(DTDSRC)
	asciidoc --unsafe sync.xsd.txt

CryptographicAlgorithms.html: CryptographicAlgorithms.txt
	asciidoc CryptographicAlgorithms.txt

InterestMessage.html: InterestMessage.txt
	asciidoc InterestMessage.txt

CanonicalOrder.html: CanonicalOrder.txt
	asciidoc CanonicalOrder.txt

NameConventions.html: NameConventions.txt
	asciidoc NameConventions.txt

Timestamp.html: Timestamp.txt
	asciidoc Timestamp.txt

Registration.html: Registration.txt
	asciidoc Registration.txt

StatusResponse.html: StatusResponse.txt
	asciidoc StatusResponse.txt

SignatureGeneration.html: SignatureGeneration.txt
	asciidoc SignatureGeneration.txt

Staleness.html: Staleness.txt
	asciidoc Staleness.txt

URI.html: URI.txt
	asciidoc URI.txt

ContentObject.html: ContentObject.txt
	asciidoc ContentObject.txt

Name.html: Name.txt
	asciidoc Name.txt

BinaryEncoding.html: BinaryEncoding.txt
	asciidoc BinaryEncoding.txt

CCNxProtocol.html: CCNxProtocol.txt
	asciidoc CCNxProtocol.txt

NameEnumerationProtocol.html: NameEnumerationProtocol.txt
	asciidoc NameEnumerationProtocol.txt

RepoPolicies.html: RepoPolicies.txt
	asciidoc RepoPolicies.txt	

RepoProtocol.html: RepoProtocol.txt
	asciidoc RepoProtocol.txt	

RepositoryInfoObject.html: RepositoryInfoObject.txt
	asciidoc RepositoryInfoObject.txt

SynchronizationProtocol.html: SynchronizationProtocol.txt
	asciidoc SynchronizationProtocol.txt

CreateCollectionProtocol.html: CreateCollectionProtocol.txt
	asciidoc CreateCollectionProtocol.txt

LinkMessages.html: LinkMessages.txt
	asciidoc LinkMessages.txt

CCNDStatus.html: CCNDStatus.txt
	asciidoc CCNDStatus.txt

../index.html: ../index.txt ../../README ../../LICENSE
	asciidoc --unsafe ../index.txt

index.html: index.txt
	asciidoc index.txt

index.txt: $(INDEXED_HTML) .genindex.sh
	rm -f index.html
	sh .genindex.sh $(INDEXED_HTML) > index.txt

default html all clean depend test check shared documentation dist-docs testinstall install uninstall: _always

_always:

.PHONY: _always
