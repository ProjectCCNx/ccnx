/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn.test.repo;


import java.util.Comparator;
import java.util.TreeSet;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Utility for repository tests.
 *
 */
public class Misc {
	
	public static class CompareTester implements Comparable<CompareTester> {
		
		String _name;
		String _label;
		int _age;
		
		public CompareTester(String name, String label, int age) {
			_age = age;
			_name = name;
			_label = label;
		}
		
		@Override
		public String toString() {
			return "Name: " + _name + " Label: " + _label + " Age: " + _age;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + _age;
			result = prime * result
					+ ((_label == null) ? 0 : _label.hashCode());
			result = prime * result + ((_name == null) ? 0 : _name.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			CompareTester other = (CompareTester) obj;
			if (_age != other._age)
				return false;
			if (_label == null) {
				if (other._label != null)
					return false;
			} else if (!_label.equals(other._label))
				return false;
			if (_name == null) {
				if (other._name != null)
					return false;
			} else if (!_name.equals(other._name))
				return false;
			return true;
		}

	/*	@Override
		public int compareTo(Object obj) {
			if (this == obj)
				return 0;
			if (obj == null)
				return -1;
			if (getClass() != obj.getClass())
				return 1;
			CompareTester other = (CompareTester) obj;
			return (compareTo(other));
		}
*/
		public int compareTo(CompareTester other) {
			int compared = _name.compareTo(other._name);
			if (0 != compared)
				return compared;
			compared = _label.compareTo(other._label);
			if (0 != compared)
				return compared;
			if (_age > other._age)
				return 1;
			else if (_age < other._age)
				return -1;
			return 0;
		}

		public int compareToNameOnly(Object obj) {
			if (this == obj)
				return 0;
			if (obj == null)
				return -1;
			if (getClass() != obj.getClass())
				return 1;
			CompareTester other = (CompareTester) obj;
			int compared = _name.compareTo(other._name);
			if (0 != compared)
				return compared;
			return 0;
		}
		
		public int compareToIgnoreAge(Object obj) {
			if (this == obj)
				return 0;
			if (obj == null)
				return -1;
			if (getClass() != obj.getClass())
				return 1;
			CompareTester other = (CompareTester) obj;
			int compared = _name.compareTo(other._name);
			if (0 != compared)
				return compared;
			compared = _label.compareTo(other._label);
			if (0 != compared)
				return compared;
			return 0;
		}

	}
	
	public static class IgnoreAgeComparator implements Comparator<CompareTester> {

		public int compare(CompareTester o1, CompareTester o2) {
			if (o1 != null) {
				return o1.compareToIgnoreAge(o2);
			} if (o2 != null) {
				return o2.compareToIgnoreAge(o1);
			}
			return 0; // both null 
		}
	}

	public static class NameOnlyComparator implements Comparator<CompareTester> {

		public int compare(CompareTester o1, CompareTester o2) {
			if (o1 != null) {
				return o1.compareToNameOnly(o2);
			} if (o2 != null) {
				return o2.compareToNameOnly(o1);
			}
			return 0; // both null 
		}
	}

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}
	
	@Test
	public void miscTest() {
		
		TreeSet<CompareTester> standardCompare = new TreeSet<CompareTester>();
		TreeSet<CompareTester> ignoreAge = new TreeSet<CompareTester>(new IgnoreAgeComparator());
		TreeSet<CompareTester> nameOnly = new TreeSet<CompareTester>(new NameOnlyComparator());
		
		CompareTester [] theData = new CompareTester[]{
				new CompareTester("Name", "Label", 21),
				new CompareTester("Name", "Label", 21),
				new CompareTester("AnotherName", "ADifferentLabel", 23),
				new CompareTester("Name", "ADifferentLabel", 23),
				new CompareTester("Name", "YetADifferentLabel", 25),
				new CompareTester("Name", "ADifferentLabel", 47),
				new CompareTester("Alex", "ADifferentLabel", 47),
				new CompareTester("ProbeName", "ProbeLabel", 40)};

		for (CompareTester tester : theData) {
			standardCompare.add(tester);
			ignoreAge.add(tester);
			nameOnly.add(tester);
		}
		System.out.println("Added " + theData.length + " elements to sets with different comparators.");
		System.out.println("Standard comparator kept: " + standardCompare.size());
		System.out.println("Ignore age comparator kept: " + ignoreAge.size());
		System.out.println("Name only comparator kept: " + nameOnly.size());
		
		CompareTester [] probes = new CompareTester[]{
				new CompareTester("AnotherName", "Foodle", 106),
				new CompareTester("Alex", "ADifferentLabel", 47),
				new CompareTester("ProbeName", "ProbeLabel", 42)};
		
		for (CompareTester probe : probes) {
			System.out.println("Probe: " + probe);
			System.out.println("Standard comparator contains: " + standardCompare.contains(probe));
			System.out.println("Ignore age comparator contains: " + ignoreAge.contains(probe));
			System.out.println("Name-only comparator contains: " + nameOnly.contains(probe));
		}
	}

}
