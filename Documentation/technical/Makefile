DERIVED_HTML = NameConventions.html

default all: $(DERIVED_HTML)

clean:
	rm -f $(DERIVED_HTML)

NameConventions.html: NameConventions.txt
	asciidoc $<
