package org.tmforum.intent.handler;

import org.apache.jena.graph.Node;
import org.apache.jena.reasoner.rulesys.BuiltinRegistry;
import org.apache.jena.reasoner.rulesys.RuleContext;
import org.apache.jena.reasoner.rulesys.builtins.BaseBuiltin;

/**
 * Custom Jena rule builtins for the TIO rules, for what Jena's own builtins cannot express.
 *
 * <ul>
 *   <li>{@code lexLess(?a, ?b)} — true if the name of {@code ?a} sorts before the name of {@code ?b}.
 *       Jena's {@code lessThan} compares numbers only; the choice rule for {@code icm:chooseFrom} needs a
 *       stable order over resources so the same candidate is picked every time.</li>
 * </ul>
 */
public final class JenaBuiltins {

    private JenaBuiltins() {}

    /** Registers the builtins with Jena. Safe to call more than once. */
    public static void register() {
        BuiltinRegistry.theRegistry.register(new LexLess());
    }

    /** {@code lexLess(?a, ?b)}: URI (or literal text, or blank-node label) of a < that of b. */
    static final class LexLess extends BaseBuiltin {
        @Override
        public String getName() { return "lexLess"; }

        @Override
        public int getArgLength() { return 2; }

        @Override
        public boolean bodyCall(Node[] args, int length, RuleContext context) {
            checkArgs(length, context);
            Node a = getArg(0, args, context);
            Node b = getArg(1, args, context);
            return text(a).compareTo(text(b)) < 0;
        }

        private static String text(Node n) {
            if (n.isURI()) return n.getURI();
            if (n.isLiteral()) return n.getLiteralLexicalForm();
            return n.getBlankNodeLabel();
        }
    }
}
