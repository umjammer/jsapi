package org.jvoicexml.jsapi2.recognition.sphinx4;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.speech.recognition.RuleGrammar;

import org.jvoicexml.jsapi2.recognition.BaseRecognizer;
import org.jvoicexml.jsapi2.recognition.BaseRuleGrammar;
import org.jvoicexml.jsapi2.recognition.GrammarDefinition;

import edu.cmu.sphinx.decoder.search.Token;
import edu.cmu.sphinx.linguist.SearchState;
import edu.cmu.sphinx.linguist.flat.GrammarState;
import edu.cmu.sphinx.linguist.language.grammar.Grammar;
import edu.cmu.sphinx.linguist.language.grammar.GrammarArc;
import edu.cmu.sphinx.linguist.language.grammar.GrammarNode;
import edu.cmu.sphinx.util.LogMath;
import edu.cmu.sphinx.util.props.PropertyException;
import edu.cmu.sphinx.util.props.PropertySheet;

/**
 * Sphinx4 SRGS grammar container.
 * 
 * <p>
 * This is a grammar for a linguist in sphinx4. There can not be more than one
 * grammar per linguist so we cheat by having a single grammar which contains
 * all the active grammars from the GrammarManager.
 * </p>
 * 
 * @author Stefan Radomski
 * @author Dirk Schnelle-Walka
 */

public class SRGSGrammarContainer extends Grammar {

    private static final Logger LOGGER = Logger
            .getLogger(Sphinx4Recognizer.class.getName());

    /**
     * The GrammarDefinitions as set through loadGrammars from the
     * GrammarManager
     */
    private Map<String, GrammarDefinition> grammarDefs = new HashMap<String, GrammarDefinition>();

    /** All active SRGSGrammars */
    private Map<String, SRGSGrammar> grammars = new HashMap<String, SRGSGrammar>();

    /** The initial node for the searchGraph of the linguist */
    private GrammarNode firstNode = null;

    /** The rule grammar as the union of all contained grammars */
    private BaseRuleGrammar ruleGrammar = null;

    /** All GrammarNodes of contained grammars plus the firstNode */
    private Set<GrammarNode> grammarNodes = new LinkedHashSet<GrammarNode>();

    /** The JSAPI recognizer. */
    private BaseRecognizer recognizer;

    /**
     * Sets the recognizer.
     * 
     * @param rec
     *            the recognizer
     */
    public void setRecognizer(final BaseRecognizer rec) {
        recognizer = rec;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void newProperties(final PropertySheet ps) throws PropertyException {
        super.newProperties(ps);
    }

    /**
     * Load all the given grammars into this one.
     * 
     * The argument contains all active grammars with their names, their XML
     * representation and an indication whether they have changed.
     * 
     * @param grammarDefinitions
     *            The set of all grammars from the GrammarManager
     * @throws IOException
     *             error loading the gramamrs
     */
    public synchronized void loadGrammars(
            Collection<org.jvoicexml.jsapi2.recognition.GrammarDefinition> grammarDefinitions)
            throws IOException {
        grammarDefs.clear();
        for (GrammarDefinition definition : grammarDefinitions) {
            grammarDefs.put(definition.getName(), definition);
        }
        commitChanges();
    }

    /**
     * Create the grammar.
     * 
     * @return the initial node of the Grammar
     */
    @Override
    protected synchronized GrammarNode createGrammar() throws IOException {
        commitChanges();
        return firstNode;
    }

    /**
     * Returns the initial node for the grammar.
     * 
     * The sphinx4 linguist will rebuild its searchGraph iff this is a different
     * object than last time. It will check for every call to recognize() by the
     * RecognizerThread started in handleResume().
     * 
     * @return the initial grammar node
     */
    @Override
    public synchronized GrammarNode getInitialNode() {
        return firstNode;
    }

    @Override
    public synchronized int getNumNodes() {
        return grammarNodes.size();
    }

    @Override
    public synchronized Set<GrammarNode> getGrammarNodes() {
        return grammarNodes;
    }

    /**
     * The {@link Sphinx4ResultListener} asked us for the grammar that produced
     * this list of tokens.
     * 
     * @param token
     *            the processed token
     * @return the rule grammar used to produce the list of tokens
     */
    public synchronized RuleGrammar getRuleGrammar(final Token token) {
        if (token == null) {
            return ruleGrammar;
        }
        for (SRGSGrammar grammar : grammars.values()) {
            final Collection<GrammarNode> nodes = grammar.getGrammarNodes();
            if (nodes != null) {
                final SearchState state = token.getSearchState();
                if (state instanceof GrammarState) {
                    final GrammarState grammarState = (GrammarState) state;
                    final GrammarNode node = grammarState.getGrammarNode();
                    if (nodes.contains(node)) {
                        return grammar.getRuleGrammar();
                    }
                }
            }
        }
        return ruleGrammar;
    }

    /**
     * Commit all pending changes.
     * 
     * All active grammars are in grammarDefs, if they were changed, they have
     * their hasChanges flag set. See if there is a new grammar, if an existing
     * one was deactivated or there have been changes to an active one. Build
     * new grammars in grammars hash and adapt firstNaode and ruleGrammar.
     * 
     * @throws IOException
     *             error creating the grammar
     */
    public synchronized void commitChanges() throws IOException {

        // we need a ruleGrammar in any case
        if (ruleGrammar == null) {
            ruleGrammar = new BaseRuleGrammar(recognizer, "srgs_container");
        }

        boolean existsChanges = false;

        // name of all active grammars
        HashSet<String> activeGrammarNames = new HashSet<String>();
        activeGrammarNames.addAll(grammarDefs.keySet());

        // is an active grammar to be removed?
        for (String name : grammars.keySet()) {
            if (!activeGrammarNames.contains(name)) {
                grammars.remove(name);
                existsChanges = true;
            }
        }

        // is there a new or changed grammar?
        for (String grammarName : activeGrammarNames) {
            GrammarDefinition grammarDef = grammarDefs.get(grammarName);
            if (!grammars.containsKey(grammarName) || grammarDef.hasChanged()) {
                // no grammar with that name yet or changes to the grammar
                existsChanges = true;

                // reload the grammar (TODO: reuse existing object?)
                SRGSGrammar grammar = new SRGSGrammar(recognizer, false, false,
                        false, false, dictionary);
                grammar.setGrammarName(grammarName);
                String grammarString = grammarDef.getGrammar();
                grammar.loadSRGS(grammarString);

                // insert or replace new grammar
                grammars.put(grammarName, grammar);
            }
        }

        // rebuild ruleGrammar, firstNode and the set of all grammar nodes
        if (existsChanges) {
            // If we did not yet create a firstnode, create an empty one
            if (firstNode == null) {
                firstNode = createGrammarNode("<sil>");
                grammarNodes.clear();
                grammarNodes.add(firstNode);
            }

            for (SRGSGrammar grammar : grammars.values()) {
                GrammarNode srgsStart = grammar.getInitialNode();
                /**
                 * Every SRGS Grammar starts with <sil>, drop it and add
                 * transitions from our firstNode.
                 */
                for (GrammarArc transition : srgsStart.getSuccessors()) {
                    firstNode.add(transition.getGrammarNode(), LogMath.LOG_ONE);
                }

                /**
                 * Add all the rules to the rule grammar for JSAPI2.
                 */
                for (String name : grammar.getRuleGrammar().listRuleNames()) {
                    ruleGrammar.addRule(grammar.getRuleGrammar().getRule(name));
                }

                // Gather all grammar nodes
                grammarNodes.addAll(grammar.getGrammarNodes());
            }
            
        }

        // Create an empty grammar if none exists so far since sphinx is not
        // able to start up without a grammar
        if (firstNode == null) {
            firstNode = createGrammarNode("<sil>");
            grammarNodes.clear();
            grammarNodes.add(firstNode);
        }

        if (LOGGER.isLoggable(Level.INFO)) {
            StringBuilder sb = new StringBuilder();
            for (String activeGrammar : grammars.keySet()) {
                sb.append(activeGrammar + " ");
            }
            LOGGER.info("Activate grammars: " + sb.toString());
        }
    }
}
