/*
 [The "BSD licence"]
 Copyright (c) 2009 Terence Parr
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:
 1. Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
 2. Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
 3. The name of the author may not be used to endorse or promote products
    derived from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package org.stringtemplate;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

public class Interpreter {
    public static final int OPTION_ANCHOR       = 0;
    public static final int OPTION_FORMAT       = 1;
    public static final int OPTION_NULL         = 2;
    public static final int OPTION_SEPARATOR    = 3;
    public static final int OPTION_WRAP         = 4;

    public static final int DEFAULT_OPERAND_STACK_SIZE = 100;

    /*
    public static String[] funcName =
        { "first", "last", "rest", "trunc", "strip", "length", "strlen" };
     */

    public static Map<String, Short> funcs = new HashMap<String, Short>() {
        {
            put("first", BytecodeDefinition.INSTR_FIRST);
            put("last", BytecodeDefinition.INSTR_LAST);
            put("rest", BytecodeDefinition.INSTR_REST);
            put("trunc", BytecodeDefinition.INSTR_TRUNC);
            put("strip", BytecodeDefinition.INSTR_STRIP);
            put("trim", BytecodeDefinition.INSTR_TRIM);
            put("length", BytecodeDefinition.INSTR_LENGTH);
            put("strlen", BytecodeDefinition.INSTR_STRLEN);
        }
    };    

    /** Operand stack, grows upwards */
    Object[] operands = new Object[DEFAULT_OPERAND_STACK_SIZE];
    int sp = -1;        // stack pointer register
    
    /** Exec st with respect to this group. Once set in ST.toString(),
     *  it should be fixed.  ST has group also.
     */
    STGroup group;
    
    public boolean trace = false;

    public Interpreter(STGroup group) {
        this.group = group;
    }

    public int exec(STWriter out, ST self) {
        int n = 0; // how many char we write out
        int nameIndex = 0;
        int addr = 0;
        String name = null;
        Object o = null;
        ST st = null;
        Object[] options = null;
        int ip = 0;
        byte[] code = self.code.instrs;        // which code block are we executing
        while ( ip < self.code.codeSize ) {
            if ( trace ) trace(self, ip);
            short opcode = code[ip];
            ip++; //jump to next instruction or first byte of operand
            switch (opcode) {
            case BytecodeDefinition.INSTR_LOAD_STR :
                int strIndex = getShort(code, ip);
                ip += 2;
                operands[++sp] = self.code.strings[strIndex];
                break;
            case BytecodeDefinition.INSTR_LOAD_ATTR :
                nameIndex = getShort(code, ip);
                ip += 2;
                name = self.code.strings[nameIndex];
                operands[++sp] = self.getAttribute(name);
                break;
            case BytecodeDefinition.INSTR_LOAD_IT :
                if ( self.attributes!=null ) o = self.attributes.get("it");
                else o = null;
                operands[++sp] = o;
                break;
            case BytecodeDefinition.INSTR_LOAD_PROP :
                nameIndex = getShort(code, ip);
                ip += 2;
                o = operands[sp--];
                name = self.code.strings[nameIndex];
                operands[++sp] = rawGetObjectProperty(o, name);
                break;
            case BytecodeDefinition.INSTR_LOAD_PROP_IND :
                name = (String)operands[sp--];
                operands[sp] = rawGetObjectProperty(operands[sp], name);
                break;
            case BytecodeDefinition.INSTR_NEW :
                nameIndex = getShort(code, ip);
                ip += 2;
                name = self.code.strings[nameIndex];
                st = group.getEmbeddedInstanceOf(self, name);
                if ( st == null ) System.err.println("no such template "+name);
                operands[++sp] = st;
                break;
            case BytecodeDefinition.INSTR_NEW_IND:
                name = (String)operands[sp--];
                st = group.getEmbeddedInstanceOf(self, name);
                if ( st == null ) System.err.println("no such template "+name);
                operands[++sp] = st;
                break;
            case BytecodeDefinition.INSTR_STORE_ATTR:
                nameIndex = getShort(code, ip);
                name = self.code.strings[nameIndex];
                ip += 2;
                o = operands[sp--];    // value to store
                st = (ST)operands[sp]; // store arg in ST on top of stack
                st.rawSetAttribute(name, o);
                break;
            case BytecodeDefinition.INSTR_STORE_SOLE_ARG :
                // unnamed arg, set to sole arg (or first if multiple)
                o = operands[sp--];    // value to store
                st = (ST)operands[sp]; // store arg in ST on top of stack
                int nargs = 0;
                if ( st.code.formalArguments!=null ) {
                    nargs = st.code.formalArguments.size();
                }
                if ( nargs!=1 ) {
                    System.err.println("arg mismatch; expecting 1, found "+nargs);
                }
                else {
                    Iterator it = st.code.formalArguments.keySet().iterator();
                    name = (String)it.next();
                    st.rawSetAttribute(name, o);
                }
                break;
            case BytecodeDefinition.INSTR_STORE_OPTION:
                int optionIndex = getShort(code, ip);
                ip += 2;
                o = operands[sp--];    // value to store
                options = (Object[])operands[sp]; // get options
                options[optionIndex] = o; // store value into options on stack
                break;
            case BytecodeDefinition.INSTR_WRITE :
                o = operands[sp--];
                n += writeObject(out, o, null);
                break;
            case BytecodeDefinition.INSTR_WRITE_OPT :
                options = (Object[])operands[sp--]; // get options
                o = operands[sp--];                 // get option to write
                n += writeObject(out, o, options);
                break;
            case BytecodeDefinition.INSTR_MAP :
                name = (String)operands[sp--];
                o = operands[sp--];
                map(self,o,name);
                break;
            case BytecodeDefinition.INSTR_ROT_MAP :
                int nmaps = getShort(code, ip);
                ip += 2;
                List<String> templates = new ArrayList<String>();
                for (int i=nmaps-1; i>=0; i--) templates.add((String)operands[sp-i]);
                sp -= nmaps;
                o = operands[sp--];
                if ( o!=null ) rot_map(self,o,templates);
                break;
            case BytecodeDefinition.INSTR_BR :
                ip = getShort(code, ip);
                break;
            case BytecodeDefinition.INSTR_BRF :
                addr = getShort(code, ip);
                ip += 2;
                o = operands[sp--]; // <if(expr)>...<endif>
                if ( !testAttributeTrue(o) ) ip = addr; // jump
                break;
            case BytecodeDefinition.INSTR_BRT :
                addr = getShort(code, ip);
                ip += 2;
                o = operands[sp--]; // <if(expr)>...<endif>
                if ( testAttributeTrue(o) ) ip = addr; // jump
                break;
            case BytecodeDefinition.INSTR_OPTIONS :
                operands[++sp] = new Object[Compiler.NUM_OPTIONS];
                break;
            case BytecodeDefinition.INSTR_LIST :
                operands[++sp] = new ArrayList<Object>();
                break;
            case BytecodeDefinition.INSTR_ADD :
                o = operands[sp--];             // pop value
                List<Object> list = (List<Object>)operands[sp]; // don't pop list
                addToList(list, o);
                break;
            case BytecodeDefinition.INSTR_TOSTR :
                // replace with string value; early eval
                operands[sp] = toString(operands[sp]);
                break;
            case BytecodeDefinition.INSTR_FIRST  :
                operands[sp] = first(operands[sp]);
                break;
            case BytecodeDefinition.INSTR_LAST   :
                operands[sp] = last(operands[sp]);
                break;
            case BytecodeDefinition.INSTR_REST   :
                operands[sp] = rest(operands[sp]);
                break;
            case BytecodeDefinition.INSTR_TRUNC  :
                operands[sp] = trunc(operands[sp]);
                break;
            case BytecodeDefinition.INSTR_STRIP  :
                operands[sp] = strip(operands[sp]);
                break;
            case BytecodeDefinition.INSTR_TRIM   :
                o = operands[sp--];
                if ( o.getClass() == String.class ) {
                    operands[++sp] = ((String)o).trim();
                }
                else System.err.println("strlen(non string)");
                break;
            case BytecodeDefinition.INSTR_LENGTH :
                operands[sp] = length(operands[sp]);
                break;
            case BytecodeDefinition.INSTR_STRLEN :
                o = operands[sp--];
                if ( o.getClass() == String.class ) {
                    operands[++sp] = ((String)o).length();
                }
                else System.err.println("strlen(non string)");
                break;
            default :
                System.err.println("Invalid bytecode: "+opcode+" @ ip="+(ip-1));
                self.code.dump();
            }
        }
        return n;
    }

    protected int writeObject(STWriter out, Object o, Object[] options) {
        // precompute all option values (render all the way to strings) 
        String[] optionStrings = null;
        if ( options!=null ) {
            optionStrings = new String[options.length];
            for (int i=0; i<Compiler.NUM_OPTIONS; i++) {
                optionStrings[i] = toString(options[i]);
            }
        }
        return writeObject(out, o, optionStrings);
    }

    protected int writeObject(STWriter out, Object o, String[] options) {
        int n = 0;
        if ( o == null ) {
            if ( options!=null && options[OPTION_NULL]!=null ) {
                try { n = out.write(options[OPTION_NULL]); }
                catch (IOException ioe) {
                    System.err.println("can't write "+o);
                }
            }
            return n;
        }
        if ( o instanceof ST ) {
            n = exec(out, (ST)o);
            return n;
        }
        o = convertAnythingIteratableToIterator(o); // normalize
        try {
            if ( o instanceof Iterator) n = writeIterator(out, o, options);
            else n = out.write(o.toString());
        }
        catch (IOException ioe) {
            System.err.println("can't write "+o);
        }
        return n;
    }

    protected int writeIterator(STWriter out, Object o, String[] options) throws IOException {
        if ( o==null ) return 0;
        int n = 0;
        Iterator it = (Iterator)o;
        String separator = null;
        if ( options!=null ) separator = options[OPTION_SEPARATOR];
        boolean seenAValue = false;
        int i = 0;
        while ( it.hasNext() ) {
            Object iterValue = it.next();
            // Emit separator if we're beyond first value
            boolean needSeparator = seenAValue &&
                separator!=null &&            // we have a separator and
                (iterValue!=null ||           // either we have a value
                 options[OPTION_NULL]!=null); // or no value but null option
            if ( needSeparator ) n += out.writeSeparator(separator);
            int nw = writeObject(out, iterValue, options);
            if ( nw > 0 ) seenAValue = true;
            n += nw;
            i++;
        }
        return n;
    }

    protected void map(ST self, Object attr, final String name) {
        rot_map(self, attr, new ArrayList<String>() {{add(name);}});
        /*
        if ( attr==null ) {
            operands[++sp] = null;
            return;
        }
        attr = convertAnythingIteratableToIterator(attr);
        if ( attr instanceof Iterator ) {
            List<ST> mapped = new ArrayList<ST>();
            Iterator iter = (Iterator)attr;
            int i0 = 0;
            int i = 1;
            while ( iter.hasNext() ) {
                Object iterValue = iter.next();
                ST st = group.getEmbeddedInstanceOf(self, name);
                setSoleArgument(st, iterValue);
                st.rawSetAttribute("i0", i0);
                st.rawSetAttribute("i", i);
                mapped.add(st);
            }
            operands[++sp] = mapped;
            //System.out.println("mapped="+mapped);
        }
        else { // map template to single value
            ST st = group.getInstanceOf(name);
            setSoleArgument(st, attr);
            st.rawSetAttribute("i0", 0);
            st.rawSetAttribute("i", 1);
            operands[++sp] = st;
        }
        */
    }

    // <names:a,b>
    protected void rot_map(ST self, Object attr, List<String> templates) {
        if ( attr==null ) {
            operands[++sp] = null;
            return;
        }
        attr = convertAnythingIteratableToIterator(attr);
        if ( attr instanceof Iterator ) {
            List<ST> mapped = new ArrayList<ST>();
            Iterator iter = (Iterator)attr;
            int i0 = 0;
            int i = 1;
            int ti = 0;
            while ( iter.hasNext() ) {
                Object iterValue = iter.next();
                if ( iterValue == null ) continue;
                int templateIndex = ti % templates.size(); // rotate through
                ti++;
                String name = templates.get(templateIndex);
                ST st = group.getEmbeddedInstanceOf(self, name);
                setSoleArgument(st, iterValue);
                st.rawSetAttribute("i0", i0);
                st.rawSetAttribute("i", i);
                mapped.add(st);
                i0++;
                i++;
            }
            operands[++sp] = mapped;
            //System.out.println("mapped="+mapped);
        }
        else { // if only single value, just apply first template to attribute
            ST st = group.getInstanceOf(templates.get(0));
            setSoleArgument(st, attr);
            st.rawSetAttribute("i0", 0);
            st.rawSetAttribute("i", 1);
            operands[++sp] = st;
//            map(self, attr, templates.get(1));
        }
    }

    protected void setSoleArgument(ST st, Object attr) {
        if ( st.code.formalArguments!=null ) {
            String arg = st.code.formalArguments.keySet().iterator().next();
            st.rawSetAttribute(arg, attr);
        }
        else {
            st.rawSetAttribute("it", attr);
        }
    }

    protected void addToList(List<Object> list, Object o) {
        if ( o==null ) return; // [a,b,c] lists ignore null values
        o = Interpreter.convertAnythingIteratableToIterator(o);
        if ( o instanceof Iterator ) {
            // copy of elements into our temp list
            Iterator it = (Iterator)o;
            while (it.hasNext()) list.add(it.next());
        }
        else {
            list.add(o);
        }
    }

    /** Return the first attribute if multiple valued or the attribute
     *  itself if single-valued.  Used in <names:first()>
     */
    public Object first(Object v) {
        if ( v==null ) return null;
        Object r = v;
        v = convertAnythingIteratableToIterator(v);
        if ( v instanceof Iterator ) {
            Iterator it = (Iterator)v;
            if ( it.hasNext() ) {
                r = it.next();
            }
        }
        return r;
    }

    /** Return the last attribute if multiple valued or the attribute
     *  itself if single-valued. Unless it's a list or array, this is pretty
     *  slow as it iterates until the last element.
     */
    public Object last(Object v) {
        if ( v==null ) return null;
        if ( v instanceof List ) return ((List)v).get(((List)v).size()-1);
        else if ( v.getClass().isArray() ) {
            Object[] elems = (Object[])v;
            return elems[elems.length-1];
        }
        Object last = v;
        v = convertAnythingIteratableToIterator(v);
        if ( v instanceof Iterator ) {
            Iterator it = (Iterator)v;
            while ( it.hasNext() ) {
                last = it.next();
            }
        }
        return last;
    }

    /** Return everything but the first attribute if multiple valued
     *  or null if single-valued.
     */
    public Object rest(Object v) {
        if ( v==null ) {
            return null;
        }
        if ( v instanceof List ) { // optimize list case
            List elems = (List)v;
            if ( elems.size()<=1 ) return null;
            return elems.subList(1, elems.size());
        }
        Object theRest = v; // else iterate and copy 
        v = convertAnythingIteratableToIterator(v);
        if ( v instanceof Iterator ) {
            List a = new ArrayList();
            Iterator it = (Iterator)v;
            if ( !it.hasNext() ) {
                return null; // if not even one value return null
            }
            it.next(); // ignore first value
            while (it.hasNext()) {
                Object o = (Object) it.next();
                if ( o!=null ) a.add(o);
            }
            return a;
        }
        else {
            theRest = null;  // rest of single-valued attribute is null
        }
        return theRest;
    }

    /** Return all but the last element.  trunc(x)=null if x is single-valued. */
    public Object trunc(Object v) {
        if ( v ==null ) {
            return null;
        }
        if ( v instanceof List ) { // optimize list case
            List elems = (List)v;
            if ( elems.size()<=1 ) return null;
            return elems.subList(0, elems.size()-1);
        }
        v = convertAnythingIteratableToIterator(v);
        if ( v instanceof Iterator ) {
            List a = new ArrayList();
            Iterator it = (Iterator) v;
            while (it.hasNext()) {
                Object o = (Object) it.next();
                if ( it.hasNext() ) a.add(o); // only add if not last one
            }
            return a;
        }
        return null; // trunc(x)==null when x single-valued attribute
    }

    /** Return a new list w/o null values. */
    public Object strip(Object v) {
        if ( v ==null ) {
            return null;
        }
        v = convertAnythingIteratableToIterator(v);
        if ( v instanceof Iterator ) {
            List a = new ArrayList();
            Iterator it = (Iterator) v;
            while (it.hasNext()) {
                Object o = (Object) it.next();
                if ( o!=null ) a.add(o);
            }
            return a;
        }
        return v; // strip(x)==x when x single-valued attribute
    }

    /** Return the length of a mult-valued attribute or 1 if it is a
     *  single attribute. If attribute is null return 0.
     *  Special case several common collections and primitive arrays for
     *  speed. This method by Kay Roepke from v3.
     */
    public Object length(Object v) {
        if ( v == null) return 0;
        int i = 1;      // we have at least one of something. Iterator and arrays might be empty.
        if ( v instanceof Map ) i = ((Map)v).size();
        else if ( v instanceof Collection ) i = ((Collection)v).size();
        else if ( v instanceof Object[] ) i = ((Object[])v).length;
        else if ( v instanceof String[] ) i = ((String[])v).length;
        else if ( v instanceof int[] ) i = ((int[])v).length;
        else if ( v instanceof long[] ) i = ((long[])v).length;
        else if ( v instanceof float[] ) i = ((float[])v).length;
        else if ( v instanceof double[] ) i = ((double[])v).length;
        else if ( v instanceof Iterator) {
            Iterator it = (Iterator)v;
            i = 0;
            while ( it.hasNext() ) {
                it.next();
                i++;
            }
        }
        return i;
    }

    public Object strlen(Object v) {
        return null;
    }

    /** Exec builtin function; could do with pseudo func ptrs but it's
     *  weird in Java and harder to port to other languages.  I'm doing
     *  a simple func lookup via int instead.
    protected Object func(int func, Object value) {
        switch ( func ) {
            case FUNC_FIRST  : return first(value);
            case FUNC_LAST   : return last(value);
            case FUNC_REST   : return rest(value);
            case FUNC_TRUNC  : return trunc(value);
            case FUNC_STRIP  : return strip(value);
            case FUNC_TRIM   :
                if ( value.getClass() == String.class ) {
                    return ((String)value).trim();
                }
                else System.err.println("strlen(non string)");
                break;
            case FUNC_LENGTH : return length(value);
            case FUNC_STRLEN :
                if ( value.getClass() == String.class ) {
                    return ((String)value).length();
                }
                else System.err.println("strlen(non string)"); 
                break;
            default :
                System.err.println("no such func: "+func);
        }
        return null;
    }
     */
        
    protected String toString(Object value) {
        if ( value!=null ) {
            if ( value.getClass()==String.class ) return (String)value;
            // if not string already, must evaluate it
            StringWriter sw = new StringWriter();
            writeObject(new NoIndentWriter(sw), value, null);
            return sw.toString();
        }
        return null;
    }
    
    protected static Object convertAnythingIteratableToIterator(Object o) {
        Iterator iter = null;
        if ( o == null ) return null;
        if ( o instanceof Collection)      iter = ((Collection)o).iterator();
        else if ( o.getClass().isArray() ) iter = new ArrayIterator(o);
        else if ( o instanceof Map)        iter = ((Map)o).values().iterator();
        else if ( o instanceof Iterator )  iter = (Iterator)o;
        if ( iter==null ) return o;
        return iter;
    }

    protected boolean testAttributeTrue(Object a) {
        if ( a==null ) return false;
        if ( a instanceof Boolean ) return ((Boolean)a).booleanValue();
        if ( a instanceof Collection ) return ((Collection)a).size()>0;
        if ( a instanceof Map ) return ((Map)a).size()>0;
        if ( a instanceof Iterator ) return ((Iterator)a).hasNext();
        return true; // any other non-null object, return true--it's present
    }

    protected Object rawGetObjectProperty(Object o, Object property) {
        if ( o==null || property==null ) {
            // TODO: throw Ill arg if they want
            return null;
        }
        Class c = o.getClass();
        Object value = null;

        // try getXXX and isXXX properties

        // look up using reflection
        String propertyName = (String)property;
        String methodSuffix = Character.toUpperCase(propertyName.charAt(0))+
            propertyName.substring(1,propertyName.length());
        Method m = getMethod(c,"get"+methodSuffix);
        if ( m==null ) {
            m = getMethod(c, "is"+methodSuffix);
        }
        if ( m != null ) {
            // save to avoid lookup later
            //self.getGroup().cacheClassProperty(c,propertyName,m);
            try {
                value = invokeMethod(m, o, value);
            }
            catch (Exception e) {
                System.err.println("Can't get property "+propertyName+" using method get/is"+methodSuffix+
                    " from "+c.getName()+" instance");
            }
        }
        else {
            // try for a visible field
            try {
                Field f = c.getField(propertyName);
                //self.getGroup().cacheClassProperty(c,propertyName,f);
                try {
                    value = accessField(f, o, value);
                }
                catch (IllegalAccessException iae) {
                    System.err.println("Can't access property "+propertyName+" using method get/is"+methodSuffix+
                        " or direct field access from "+c.getName()+" instance");
                }
            }
            catch (NoSuchFieldException nsfe) {
                System.err.println("Class "+c.getName()+" has no such attribute: "+propertyName+
                    " in template context "+"PUT CALLSTACK HERE");
            }
        }

        return value;
    }

    protected Object accessField(Field f, Object o, Object value) throws IllegalAccessException {
        try {
            // make sure it's accessible (stupid java)
            f.setAccessible(true);
        }
        catch (SecurityException se) {
            ; // oh well; security won't let us
        }
        value = f.get(o);
        return value;
    }

    protected Object invokeMethod(Method m, Object o, Object value) throws IllegalAccessException, InvocationTargetException {
        try {
            // make sure it's accessible (stupid java)
            m.setAccessible(true);
        }
        catch (SecurityException se) {
            ; // oh well; security won't let us
        }
        value = m.invoke(o,(Object[])null);
        return value;
    }

    protected Method getMethod(Class c, String methodName) {
        Method m;
        try {
            m = c.getMethod(methodName, (Class[])null);
        }
        catch (NoSuchMethodException nsme) {
            m = null;
        }
        return m;
    }
    
    protected void trace(ST self, int ip) {
        BytecodeDisassembler dis = new BytecodeDisassembler(self.code.instrs,
                                                            self.code.instrs.length,
                                                            self.code.strings);
        StringBuilder buf = new StringBuilder();
        dis.disassembleInstruction(buf,ip);
        String name = self.name+":";
        if ( self.name==ST.UNKNOWN_NAME) name = "";
        System.out.printf("%-40s",name+buf);
        System.out.print("\tstack=[");
        for (int i = 0; i <= sp; i++) {
            Object o = operands[i];
            printForTrace(o);
        }
        System.out.print(" ], calls=");
        System.out.print(self.getEnclosingInstanceStackString());
        System.out.println();
    }

    protected void printForTrace(Object o) {
        if ( o instanceof ST ) {
            System.out.print(" "+((ST)o).name+"()");
            return;
        }
        o = convertAnythingIteratableToIterator(o);
        if ( o instanceof Iterator ) {
            Iterator it = (Iterator)o;
            System.out.print(" [");
            while ( it.hasNext() ) {
                Object iterValue = it.next();
                printForTrace(iterValue);
            }
            System.out.print(" ]");
        }
        else {
            System.out.print(" "+o);
        }
    }

    public static int getInt(byte[] memory, int index) {
        int b1 = memory[index++]&0xFF; // mask off sign-extended bits
        int b2 = memory[index++]&0xFF;
        int b3 = memory[index++]&0xFF;
        int b4 = memory[index++]&0xFF;
        int word = b1<<(8*3) | b2<<(8*2) | b3<<(8*1) | b4;
        return word;
    }

    public static int getShort(byte[] memory, int index) {
        int b1 = memory[index++]&0xFF; // mask off sign-extended bits
        int b2 = memory[index++]&0xFF;
        int word = b1<<(8*1) | b2;
        return word;
    }
}
