
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
	ccnrm			\
	ccn_repo		\

# Yes, this is gmake specific
DERIVED_HTML =	$(foreach name,$(PAGE_NAMES),$(name).1.html)
DERIVED_PDF = $(foreach name,$(PAGE_NAMES),$(name).1.pdf)
DERIVED_MAN = $(foreach name,$(PAGE_NAMES),$(name).1)

DERIVED_INDEX = index.html index.txt
#DEBRIS = .tagname.csv
DEBRIS = $(foreach name,$(PAGE_NAMES),$(name).1.fo) $(foreach name,$(PAGE_NAMES),$(name).1.xml)
SRC_HTML = # any checked-in html files should go here
INDEXED_HTML = $(DERIVED_HTML) $(SRC_HTML)

all documentation dist-docs: html $(DERIVED_MAN) $(DERIVED_INDEX)

html: $(DERIVED_HTML)

pdf: $(DERIVED_PDF)

clean:
	rm -f $(DERIVED_HTML) $(DERIVED_PDF) $(DERIVED_MAN) $(DERIVED_INDEX) $(DEBRIS)

index.html: index.txt
	asciidoc index.txt

index.txt: $(INDEXED_HTML) .genindex.sh
	rm -f index.html
	sh .genindex.sh $(INDEXED_HTML) > index.txt

%.html : %.txt
	asciidoc -d manpage $<

%.pdf : %.txt 
	a2x -f pdf -d manpage $<

%.1 : %.1.txt
	a2x -f manpage -d manpage $<

default all clean depend test check shared documentation dist-docs testinstall install uninstall: _always

_always:

.PHONY: _always
