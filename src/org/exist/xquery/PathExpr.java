/*
 *  eXist Native XML Database
 *  Copyright (C) 2001-06,  Wolfgang M. Meier (wolfgang@exist-db.org)
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Library General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Library General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 * $Id$
 */
package org.exist.xquery;

import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;
import org.exist.dom.DocumentSet;
import org.exist.security.xacml.XACMLSource;
import org.exist.xquery.parser.XQueryAST;
import org.exist.xquery.util.ExpressionDumper;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;
import org.xmldb.api.base.CompiledExpression;

/**
 * PathExpr is just a sequence of XQuery/XPath expressions, which will be called
 * step by step.
 * 
 * @author Wolfgang Meier (wolfgang@exist-db.org)
 */
public class PathExpr extends AbstractExpression implements CompiledXQuery,
        CompiledExpression {

    protected final static Logger LOG = Logger.getLogger(PathExpr.class);

    protected boolean keepVirtual = false;

    protected List steps = new ArrayList();

    protected boolean inPredicate = false;
    
    protected XACMLSource source;
    
    public PathExpr(XQueryContext context) {
        super(context);
    }
    
    public void setSource(XACMLSource source) {
    	this.source = source;
    }
    public XACMLSource getSource() {
    	return source;
    }

    /**
     * Add an arbitrary expression to this object's list of child-expressions.
     * 
     * @param s
     */
    public void add(Expression s) {
        steps.add(s);
    }

    /**
     * Add all the child-expressions from another PathExpr to this object's
     * child-expressions.
     * 
     * @param path
     */
    public void add(PathExpr path) {
        Expression expr;
        for (Iterator i = path.steps.iterator(); i.hasNext();) {
            expr = (Expression) i.next();
            add(expr);
        }
    }

    /**
     * Add another PathExpr to this object's expression list.
     * 
     * @param path
     */
    public void addPath(PathExpr path) {
        steps.add(path);
    }

    /**
     * Add a predicate expression to the list of expressions. The predicate is
     * added to the last expression in the list.
     * 
     * @param pred
     */
    public void addPredicate(Predicate pred) {
        Expression e = (Expression) steps.get(steps.size() - 1);
        if (e instanceof Step) ((Step) e).addPredicate(pred);
    }

    /* (non-Javadoc)
     * @see org.exist.xquery.Expression#analyze(org.exist.xquery.Expression)
     */
    public void analyze(AnalyzeContextInfo contextInfo) throws XPathException {
    	contextInfo.setParent(this);
        inPredicate = (contextInfo.getFlags() & IN_PREDICATE) > 0;
        contextId = contextInfo.getContextId();
        
        for (int i = 0; i < steps.size(); i++) {
            // if this is a sequence of steps, the IN_PREDICATE flag
            // is only passed to the first step, so it has to be removed
            // for subsequent steps  
            Expression expr = (Expression) steps.get(i);
            if ((contextInfo.getFlags() & IN_PREDICATE) > 0 ) {
                if(i == 1) {
                	//take care : predicates in predicates are not marked as such ! -pb
                    contextInfo.setFlags(contextInfo.getFlags() & (~IN_PREDICATE));
                    //Where clauses should be identified. TODO : pass bound variable's inputSequence ? -pb
                    if ((contextInfo.getFlags() & IN_WHERE_CLAUSE) == 0)
                        contextInfo.setContextId(Expression.NO_CONTEXT_ID);                    
                }
            }
            expr.analyze(contextInfo);
        }
    }
    
    public Sequence eval(Sequence contextSequence, Item contextItem) throws XPathException {
        if (context.getProfiler().isEnabled()) {
            context.getProfiler().start(this);       
            context.getProfiler().message(this, Profiler.DEPENDENCIES, "DEPENDENCIES", Dependency.getDependenciesName(this.getDependencies()));
            if (contextSequence != null)
                context.getProfiler().message(this, Profiler.START_SEQUENCES, "CONTEXT SEQUENCE", contextSequence);
            if (contextItem != null)
                context.getProfiler().message(this, Profiler.START_SEQUENCES, "CONTEXT ITEM", contextItem.toSequence());
        }
        
        Sequence result = null;        
        if (steps.size() == 0) {
            result = Sequence.EMPTY_SEQUENCE;
        } else {
            //we will filter out nodes from the contextSequence
            result = contextSequence;
            
            DocumentSet contextDocs = null;
            Expression expr = (Expression) steps.get(0);
            if (expr instanceof VariableReference) {
                Variable var = ((VariableReference) expr).getVariable();
                //TOUNDERSTAND : how null could be possible here ? -pb
                if (var != null) 
                    contextDocs = var.getContextDocs();            
            }
            //contextDocs == null *is* significant
            setContextDocSet(contextDocs);
            
            //To prevent processing nodes after atomic values...
            //TODO : let the parser do it ? -pb
            boolean gotAtomicResult = false;  
            
            for (Iterator iter = steps.iterator(); iter.hasNext();) {
                
                expr = (Expression) iter.next();
                
                //TODO : maybe this could be detected by the parser ? -pb    
                if (gotAtomicResult && !Type.subTypeOf(expr.returnsType(), Type.NODE)
                        //Ugly workaround to allow preceding *text* nodes.
                        && !(expr instanceof EnclosedExpr)) {
                    throw new XPathException("XPTY0019: left operand of '/' must be a node. Got '" + 
                            Type.getTypeName(result.getItemType()) + Cardinality.toString(result.getCardinality()) + "'");                    
                }                 
                
                //contextDocs == null *is* significant
                expr.setContextDocSet(contextDocs);
          
                //DESIGN : first test the dependency then the result
                if ((Dependency.dependsOn(expr, Dependency.CONTEXT_ITEM) ||
                		Dependency.dependsOn(expr, Dependency.CONTEXT_POSITION)) && 
                		result != null && !result.isEmpty()) {
                      
                    Sequence exprResult = new ValueSequence();
                    
                    //Restore a position which may have been modified by inner expressions 
                    int p = context.getContextPosition();
                    
                    for (SequenceIterator iterInner = result.iterate(); iterInner.hasNext(); p++) {
                        context.setContextPosition(p);
                        LOG.debug("Pos: " + p);
                        Item current = iterInner.nextItem();   
                        //0 or 1 item
                        if (!result.hasMany())
                        	exprResult = expr.eval(result, current);
                        else {
                        	exprResult.addAll(expr.eval(result, current));
                        }
                    }
                    result = exprResult;  
                                   
                } else {
                	result = expr.eval(result);
                }
            
                //TOUNDERSTAND : why did I have to write this test :-) ? -pb
                //it looks like an empty sequence could be considered as a sub-type of Type.NODE
                //well, no so stupid I think...    
                if (steps.size() > 1 && !result.isEmpty() && !Type.subTypeOf(result.getItemType(), Type.NODE))
                    gotAtomicResult = true;

                if(steps.size() > 1)
                    // remove duplicate nodes if this is a path 
                    // expression with more than one step
                    result.removeDuplicates();                
            }
        }
        
        if (context.getProfiler().isEnabled()) 
            context.getProfiler().end(this, "", result);
        
        return result;
    }

    public XQueryContext getContext() {
        return context;
    }
    
    public DocumentSet getDocumentSet() {
        return null;
    }

    public Expression getExpression(int pos) {
        return (Expression)steps.get(pos);
    }

    public Expression getLastExpression() {
        if (steps.size() == 0) 
            return null;
        return (Expression)steps.get(steps.size() - 1);
    }

    public int getLength() {
        return steps.size();
    }

    /* (non-Javadoc)
     * @see org.exist.xquery.Expression#dump(org.exist.xquery.util.ExpressionDumper)
     */
    public void dump(ExpressionDumper dumper) {
//        dumper.startIndent();
        Expression next = null;
        int count = 0;
        for (Iterator iter = steps.iterator(); iter.hasNext(); count++) {
        	next = (Expression) iter.next(); 
        	//Open a first parenthesis
        	if (next instanceof LogicalOp)
        		dumper.display('(');
            if(count > 0) {
                if(next instanceof Step)
                	dumper.display("/");
                else
                    dumper.nl();
            }
            next.dump(dumper);           
        }
        //Close the last parenthesis
        if (next instanceof LogicalOp)
    		dumper.display(')');
//        dumper.endIndent();
    }
    
    public String toString() { 
    	StringBuffer result = new StringBuffer();
    	Expression next = null;
    	int count = 0;
    	for (Iterator iter = steps.iterator(); iter.hasNext(); count++) {
    		next = (Expression) iter.next(); 
    		// Open a first parenthesis
    		if (next instanceof LogicalOp)
    			result.append('(');
    		if(count > 0) {
    			if(next instanceof Step)
    				result.append("/");
    			else
    				result.append(' ');
    		}
    		result.append(next.toString());           
    	}
    	// Close the last parenthesis
    	if (next instanceof LogicalOp)
    		result.append(')');
    	return result.toString();
    }
    
    public int returnsType() {
        if (steps.size() == 0) 
            return Type.NODE;         
        return ((Expression)steps.get(steps.size() - 1)).returnsType();
    }

 	public int getCardinality() {
		if (steps.size() == 0) return Cardinality.ZERO;
		return ((Expression) steps.get(steps.size() -1)).getCardinality();
	}
 	
    /*
     * (non-Javadoc)
     * 
     * @see org.exist.xquery.AbstractExpression#getDependencies()
     */
    public int getDependencies() {
        Expression next;
        int deps = 0;
        for (Iterator i = steps.iterator(); i.hasNext();) {
            next = (Expression) i.next();
            deps = deps | next.getDependencies();
        }
        return deps;
    }

    public void replaceLastExpression(Expression s) {
        if (steps.size() == 0)
            return;        
        steps.set(steps.size() - 1, s);        
    }

    public String getLiteralValue() {
        if (steps.size() == 0) 
            return "";
        Expression next = (Expression)steps.get(0);
        if (next instanceof LiteralValue) {
            try {        
                return ((LiteralValue) next).getValue().getStringValue();
            } catch (XPathException e) {
                //TODO : is there anything to do here ?
            }
        }
        if (next instanceof PathExpr)
            return ((PathExpr)next).getLiteralValue();
        return "";
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.exist.xquery.AbstractExpression#getASTNode()
     */
    public XQueryAST getASTNode() {
        XQueryAST ast = super.getASTNode();
        if (ast == null && steps.size() == 1)
            return ((Expression)steps.get(0)).getASTNode();
        return ast;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.exist.xquery.AbstractExpression#setPrimaryAxis(int)
     */
    public void setPrimaryAxis(int axis) {
        if (steps.size() > 0) 
            ((Expression)steps.get(0)).setPrimaryAxis(axis);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.exist.xquery.AbstractExpression#resetState()
     */
    public void resetState() {
    	super.resetState();
    	for (int i = 0; i < steps.size(); i++) {
            ((Expression)steps.get(i)).resetState();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.exist.xmldb.CompiledExpression#reset()
     */
    public void reset() {
        resetState();
    }
    
    /* (non-Javadoc)
	 * @see org.exist.xquery.CompiledXQuery#isValid()
	 */
	public boolean isValid() {
		return context.checkModulesValid();
	}
	
	/* (non-Javadoc)
     * @see org.exist.xquery.CompiledXQuery#dump(java.io.Writer)
     */
    public void dump(Writer writer) {
        ExpressionDumper dumper = new ExpressionDumper(writer);
        dump(dumper);
    }

	public void setContext(XQueryContext context) {
		this.context = context;
	}
}