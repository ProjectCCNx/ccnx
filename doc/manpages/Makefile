
# The order here determines the order in the generated index.
DERIVED_HTML =				\
	ccngetfile.1.html

DERIVED_INDEX = index.html index.txt
#DEBRIS = .tagname.csv
SRC_HTML = # any checked-in html files should go here
INDEXED_HTML = $(DERIVED_HTML) $(SRC_HTML)

all documentation dist-docs: $(DERIVED_HTML) $(DERIVED_INDEX)

clean:
	rm -f $(DERIVED_HTML) $(DERIVED_INDEX) $(DEBRIS)

index.html: index.txt
	asciidoc index.txt

index.txt: $(INDEXED_HTML) .genindex.sh
	rm -f index.html
	sh .genindex.sh $(INDEXED_HTML) > index.txt

%.html : %.txt
	asciidoc -d manpage $<

default all clean depend test check shared documentation dist-docs testinstall install uninstall: _always

_always:

.PHONY: _always
