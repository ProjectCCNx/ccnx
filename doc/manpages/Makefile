
# The order here determines the order in the generated index.
PAGE_NAMES = 			\
	ccnacl			\
	ccngroup		\
	ccnd			\
	ccndc			\
	ccndstart		\
	ccnexplore		\
	ccnls			\
	ccnlsrepo		\
	ccngetfile		\
	ccnputfile		\
	ccnputmeta		\
	ccngetmeta		\
	ccnrm			\
	ccn_repo		\

SRC_TXT     = $(PAGE_NAMES:=.1.txt)
DERIVED_HTML= $(PAGE_NAMES:=.1.html)
DERIVED_PDF = $(PAGE_NAMES:=.1.pdf)
DERIVED_MAN = $(PAGE_NAMES:=.1)

DERIVED_INDEX = index.html index.txt
DEBRIS = $(PAGE_NAMES:=.1.fo) $(PAGE_NAMES:=.1.xml)
SRC_HTML = # any checked-in html files should go here
INDEXED_HTML = $(DERIVED_HTML) $(SRC_HTML)

all documentation dist-docs: html $(DERIVED_MAN)

html: $(DERIVED_HTML) $(DERIVED_INDEX)

pdf: $(DERIVED_PDF)

clean:
	rm -f $(DERIVED_HTML) $(DERIVED_PDF) $(DERIVED_MAN) $(DERIVED_INDEX) $(DEBRIS)

index.html: index.txt
	asciidoc index.txt

index.txt: $(INDEXED_HTML) .genindex.sh
	rm -f index.html
	sh .genindex.sh $(INDEXED_HTML) > index.txt

$(DERIVED_HTML): $(@:.html=.txt) 
	asciidoc -a revnumber="$(CCNX_RELEASE_VERSION)" -d manpage $(@:.html=.txt)

$(DERIVED_PDF): $(@:=.txt)
	a2x -f pdf -d manpage $(@:=.txt)

$(DERIVED_MAN): $(@:=.txt)
	a2x -a revnumber="$(CCNX_RELEASE_VERSION)" -f manpage -d manpage $(@:=.txt)

default all clean depend test check shared documentation dist-docs testinstall install uninstall: _always

_always:

.PHONY: _always
