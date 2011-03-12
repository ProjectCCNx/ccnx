# 
# Part of the CCNx distribution.
#
# Copyright (C) 2011 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#
DISTRIB = AccessControlSpecs01
TEXFILES = main.tex coverpage.tex AccessControlSpecs01.tex

FIGFILES = 
BIBFILES = 

PDFFLAGS = --jobname $(DISTRIB)

default:
	: To rebuild the PDF files: $(MAKE) pdf

pdf all: $(DISTRIB).pdf

$(DISTRIB).pdf: $(TEXFILES) $(BIBFILES) $(FIGFILES)
	pdflatex $(PDFFLAGS) main.tex
	pdflatex $(PDFFLAGS) main.tex
	pdflatex $(PDFFLAGS) main.tex
	pdflatex $(PDFFLAGS) main.tex

clean:
	-rm -f *.aux *.toc *~
	-rm -f *.log
	-rm -f *.blg *.bbl $(MAIN).dvi main.ps $(DISTRIB).pdf 

