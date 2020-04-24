/*
 * (C) Copyright IBM Corp. 2019, 2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.fhir.path.evaluator;

import static com.ibm.fhir.core.util.LRUCache.createLRUCache;
import static com.ibm.fhir.path.FHIRPathDecimalValue.decimalValue;
import static com.ibm.fhir.path.FHIRPathIntegerValue.integerValue;
import static com.ibm.fhir.path.FHIRPathStringValue.EMPTY_STRING;
import static com.ibm.fhir.path.FHIRPathStringValue.stringValue;
import static com.ibm.fhir.path.util.FHIRPathUtil.empty;
import static com.ibm.fhir.path.util.FHIRPathUtil.evaluatesToBoolean;
import static com.ibm.fhir.path.util.FHIRPathUtil.getInteger;
import static com.ibm.fhir.path.util.FHIRPathUtil.getNumberValue;
import static com.ibm.fhir.path.util.FHIRPathUtil.getQuantityNode;
import static com.ibm.fhir.path.util.FHIRPathUtil.getQuantityValue;
import static com.ibm.fhir.path.util.FHIRPathUtil.getSingleton;
import static com.ibm.fhir.path.util.FHIRPathUtil.getString;
import static com.ibm.fhir.path.util.FHIRPathUtil.getStringValue;
import static com.ibm.fhir.path.util.FHIRPathUtil.getSystemValue;
import static com.ibm.fhir.path.util.FHIRPathUtil.getTemporalValue;
import static com.ibm.fhir.path.util.FHIRPathUtil.hasNumberValue;
import static com.ibm.fhir.path.util.FHIRPathUtil.hasQuantityValue;
import static com.ibm.fhir.path.util.FHIRPathUtil.hasStringValue;
import static com.ibm.fhir.path.util.FHIRPathUtil.hasSystemValue;
import static com.ibm.fhir.path.util.FHIRPathUtil.hasTemporalValue;
import static com.ibm.fhir.path.util.FHIRPathUtil.isCodedElementNode;
import static com.ibm.fhir.path.util.FHIRPathUtil.isComparableTo;
import static com.ibm.fhir.path.util.FHIRPathUtil.isFalse;
import static com.ibm.fhir.path.util.FHIRPathUtil.isQuantityNode;
import static com.ibm.fhir.path.util.FHIRPathUtil.isSingleton;
import static com.ibm.fhir.path.util.FHIRPathUtil.isStringElementNode;
import static com.ibm.fhir.path.util.FHIRPathUtil.isStringValue;
import static com.ibm.fhir.path.util.FHIRPathUtil.isTrue;
import static com.ibm.fhir.path.util.FHIRPathUtil.isTypeCompatible;
import static com.ibm.fhir.path.util.FHIRPathUtil.isUriElementNode;
import static com.ibm.fhir.path.util.FHIRPathUtil.singleton;
import static com.ibm.fhir.path.util.FHIRPathUtil.unescape;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import com.ibm.fhir.model.annotation.Constraint;
import com.ibm.fhir.model.resource.OperationOutcome.Issue;
import com.ibm.fhir.model.resource.Resource;
import com.ibm.fhir.model.type.Element;
import com.ibm.fhir.model.visitor.Visitable;
import com.ibm.fhir.path.FHIRPathBaseVisitor;
import com.ibm.fhir.path.FHIRPathBooleanValue;
import com.ibm.fhir.path.FHIRPathDateTimeValue;
import com.ibm.fhir.path.FHIRPathDateValue;
import com.ibm.fhir.path.FHIRPathLexer;
import com.ibm.fhir.path.FHIRPathNode;
import com.ibm.fhir.path.FHIRPathParser;
import com.ibm.fhir.path.FHIRPathParser.ExpressionContext;
import com.ibm.fhir.path.FHIRPathParser.ParamListContext;
import com.ibm.fhir.path.FHIRPathQuantityNode;
import com.ibm.fhir.path.FHIRPathQuantityValue;
import com.ibm.fhir.path.FHIRPathStringValue;
import com.ibm.fhir.path.FHIRPathSystemValue;
import com.ibm.fhir.path.FHIRPathTemporalValue;
import com.ibm.fhir.path.FHIRPathTimeValue;
import com.ibm.fhir.path.FHIRPathTree;
import com.ibm.fhir.path.FHIRPathType;
import com.ibm.fhir.path.exception.FHIRPathException;
import com.ibm.fhir.path.function.FHIRPathFunction;

/**
 * A FHIRPath evaluation engine that implements the FHIRPath 2.0.0 <a href="http://hl7.org/fhirpath/N1/">specification</a>
 */
public class FHIRPathEvaluator {
    private static final Logger log = Logger.getLogger(FHIRPathEvaluator.class.getName());

    public static final Collection<FHIRPathNode> SINGLETON_TRUE = singleton(FHIRPathBooleanValue.TRUE);
    public static final Collection<FHIRPathNode> SINGLETON_FALSE = singleton(FHIRPathBooleanValue.FALSE);

    private static final int EXPRESSION_CONTEXT_CACHE_MAX_ENTRIES = 512;
    private static final Map<String, ExpressionContext> EXPRESSION_CONTEXT_CACHE = createLRUCache(EXPRESSION_CONTEXT_CACHE_MAX_ENTRIES);

    private final EvaluatingVisitor visitor = new EvaluatingVisitor();

    private FHIRPathEvaluator() { }

    /**
     * Get the EvaluationContext associated with this FHIRPathEvaluator
     *
     * @return
     *     get the EvaluationContext associated with this FHIRPathEvaluator
     */
    public EvaluationContext getEvaluationContext() {
        return visitor.getEvaluationContext();
    }

    /**
     * Evaluate a FHIRPath expression
     *
     * @param expr
     *     the FHIRPath expression to evaluate
     * @return
     *     the result of evaluation as a non-null, potentially empty collection of FHIRPath nodes
     * @throws NullPointerException
     *     if any of the parameters are null
     * @throws FHIRPathException
     *     if an exception occurs during evaluation
     */
    public Collection<FHIRPathNode> evaluate(String expr) throws FHIRPathException {
        return evaluate(new EvaluationContext(), expr, empty());
    }

    /**
     * Evaluate a FHIRPath expression against a {@link Resource} or {@link Element}
     *
     * @param resourceOrElement
     *     the {@link Resource} or {@link Element}
     * @param expr
     *     the FHIRPath expression to evaluate
     * @return
     *     the result of evaluation as a non-null, potentially empty collection of FHIRPath nodes
     * @throws NullPointerException
     *     if any of the parameters are null
     * @throws FHIRPathException
     *     if an exception occurs during evaluation
     */
    public Collection<FHIRPathNode> evaluate(Visitable resourceOrElement, String expr) throws FHIRPathException {
        Objects.requireNonNull("resourceOrElement cannot be null");

        if (resourceOrElement instanceof Resource) {
            return evaluate((Resource) resourceOrElement, expr);
        } else if (resourceOrElement instanceof Element) {
            return evaluate((Element) resourceOrElement, expr);
        }

        throw new IllegalArgumentException("FHIRPath Context cannot be established for object of type " +
                resourceOrElement.getClass().getName());
    }

    /**
     * Evaluate a FHIRPath expression against a {@link Resource}
     *
     * @param resource
     *     the resource
     * @param expr
     *     the FHIRPath expression to evaluate
     * @return
     *     the result of evaluation as a non-null, potentially empty collection of FHIRPath nodes
     * @throws NullPointerException
     *     if any of the parameters are null
     * @throws FHIRPathException
     *     if an exception occurs during evaluation
     */
    public Collection<FHIRPathNode> evaluate(Resource resource, String expr) throws FHIRPathException {
        return evaluate(new EvaluationContext(resource), expr);
    }

    /**
     * Evaluate a FHIRPath expression against an {@link Element}
     *
     * @param element
     *     the element
     * @param expr
     *     the FHIRPath expression to evaluate
     * @return
     *     the result of evaluation as a non-null, potentially empty collection of FHIRPath nodes
     * @throws NullPointerException
     *     if any of the parameters are null
     * @throws FHIRPathException
     *     if an exception occurs during evaluation
     */
    public Collection<FHIRPathNode> evaluate(Element element, String expr) throws FHIRPathException {
        return evaluate(new EvaluationContext(element), expr);
    }

    /**
     * Evaluate a FHIRPath expression using an existing evaluation context
     *
     * @param evaluationContext
     *     the evaluation context
     * @param expr
     *     the FHIRPath expression to evaluate
     * @return
     *     the result of evaluation as a non-null, potentially empty collection of FHIRPath nodes
     * @throws NullPointerException
     *     if any of the parameters are null
     * @throws FHIRPathException
     *     if an exception occurs during evaluation
     */
    public Collection<FHIRPathNode> evaluate(EvaluationContext evaluationContext, String expr) throws FHIRPathException {
        return evaluate(evaluationContext, expr, evaluationContext.getTree().getRoot());
    }

    /**
     * Evaluate a FHIRPath expression using an existing evaluation context against a FHIRPath node
     *
     * @param evaluationContext
     *     the evaluation context
     * @param expr
     *     the FHIRPath expression to evaluate
     * @param node
     *     the FHIRPath node
     * @return
     *     the result of evaluation as a non-null, potentially empty collection of FHIRPath nodes
     * @throws NullPointerException
     *     if any of the parameters are null
     * @throws FHIRPathException
     *     if an exception occurs during evaluation
     */
    public Collection<FHIRPathNode> evaluate(EvaluationContext evaluationContext, String expr, FHIRPathNode node) throws FHIRPathException {
        return evaluate(evaluationContext, expr, singleton(node));
    }

    /**
     * Evaluate a FHIRPathExpression using an existing EvaluationContext against a collection of FHIRPath nodes
     *
     * @param evaluationContext
     *     the evaluation context
     * @param expr
     *     the FHIRPath expression to evaluate
     * @param initialContext
     *     the initial context as a non-null, potentially empty collection of FHIRPath nodes
     * @return
     *     the result of evaluation as a collection of FHIRPath nodes
     * @throws NullPointerException
     *     if any of the parameters are null
     * @throws FHIRPathException
     *     if an exception occurs during evaluation
     */
    public Collection<FHIRPathNode> evaluate(EvaluationContext evaluationContext, String expr, Collection<FHIRPathNode> initialContext) throws FHIRPathException {
        Objects.requireNonNull(evaluationContext);
        Objects.requireNonNull(initialContext);
        try {
            evaluationContext.setExternalConstant("context", initialContext);
            return visitor.evaluate(evaluationContext, getExpressionContext(expr), initialContext);
        } catch (Exception e) {
            throw new FHIRPathException("An error occurred while evaluating expression: " + expr, e);
        }
    }

    private static ExpressionContext getExpressionContext(String expr) {
        return EXPRESSION_CONTEXT_CACHE.computeIfAbsent(Objects.requireNonNull(expr), FHIRPathEvaluator::compile);
    }

    private static ExpressionContext compile(String expr) {
        FHIRPathLexer lexer = new FHIRPathLexer(CharStreams.fromString(expr));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        FHIRPathParser parser = new FHIRPathParser(tokens);
        return parser.expression();
    }

    /**
     * Static factory method for creating FHIRPathEvaluator instances
     *
     * @return
     *     a new FHIRPathEvaluator instance
     */
    public static FHIRPathEvaluator evaluator() {
        return new FHIRPathEvaluator();
    }

    public static class EvaluatingVisitor extends FHIRPathBaseVisitor<Collection<FHIRPathNode>> {
        private static final String SYSTEM_NAMESPACE = "System";

        private static final int IDENTIFIER_CACHE_MAX_ENTRIES = 2048;
        private static final Map<String, Collection<FHIRPathNode>> IDENTIFIER_CACHE = createLRUCache(IDENTIFIER_CACHE_MAX_ENTRIES);

        private static final int LITERAL_CACHE_MAX_ENTRIES = 128;
        private static final Map<String, Collection<FHIRPathNode>> LITERAL_CACHE = createLRUCache(LITERAL_CACHE_MAX_ENTRIES);

        private EvaluationContext evaluationContext;
        private final Stack<Collection<FHIRPathNode>> contextStack = new Stack<>();

        private int indentLevel = 0;

        private EvaluatingVisitor() { }

        private Collection<FHIRPathNode> evaluate(EvaluationContext evaluationContext, ExpressionContext expressionContext, Collection<FHIRPathNode> initialContext) {
            reset();
            this.evaluationContext = evaluationContext;
            contextStack.push(initialContext);
            Collection<FHIRPathNode> result = expressionContext.accept(this);
            contextStack.pop();
            return Collections.unmodifiableCollection(result);
        }

        private EvaluationContext getEvaluationContext() {
            return evaluationContext;
        }

        private void reset() {
            contextStack.clear();
            indentLevel = 0;
        }

        private Collection<FHIRPathNode> all(List<ExpressionContext> arguments) {
            if (arguments.size() != 1) {
                throw unexpectedNumberOfArguments(arguments.size(), "all");
            }
            ExpressionContext criteria = arguments.get(0);
            for (FHIRPathNode node : getCurrentContext()) {
                pushContext(singleton(node));
                Collection<FHIRPathNode> result = visit(criteria);
                if (evaluatesToBoolean(result) && isFalse(result)) {
                    popContext();
                    return SINGLETON_FALSE;
                }
                popContext();
            }
            return SINGLETON_TRUE;
        }

        private Collection<FHIRPathNode> as(Collection<ExpressionContext> arguments) {
            if (arguments.size() != 1) {
                throw unexpectedNumberOfArguments(arguments.size(), "as");
            }
            Collection<FHIRPathNode> result = new ArrayList<>();
            ExpressionContext typeName = arguments.iterator().next();
            String identifier = typeName.getText().replace("`", "");
            FHIRPathType type = FHIRPathType.from(identifier);
            if (type == null) {
                throw new IllegalArgumentException(String.format("Argument '%s' cannot be resolved to a valid type identifier", identifier));
            }
            for (FHIRPathNode node : getCurrentContext()) {
                FHIRPathType nodeType = node.type();
                if (SYSTEM_NAMESPACE.equals(type.namespace()) && node.hasValue()) {
                    nodeType = node.getValue().type();
                }
                if (type.isAssignableFrom(nodeType)) {
                    result.add(node);
                }
            }
            return result;
        }

        private Set<String> closure(FHIRPathType type) {
            if (SYSTEM_NAMESPACE.equals(type.namespace())) {
                return Collections.emptySet();
            }
            // compute type name closure
            Set<String> closure = new HashSet<>();
            while (!FHIRPathType.FHIR_ANY.equals(type)) {
                closure.add(type.getName());
                type = type.baseType();
            }
            return closure;
        }

        private Collection<FHIRPathNode> exists(List<ExpressionContext> arguments) {
            if (arguments.size() < 0 || arguments.size() > 1) {
                throw unexpectedNumberOfArguments(arguments.size(), "exists");
            }
            Collection<FHIRPathNode> nodes = arguments.isEmpty() ? getCurrentContext() : visit(arguments.get(0));
            return !nodes.isEmpty() ? SINGLETON_TRUE : SINGLETON_FALSE;
        }

        private Collection<FHIRPathNode> getCurrentContext() {
            if (!contextStack.isEmpty()) {
                return contextStack.peek();
            }
            return empty();
        }

        private Collection<FHIRPathNode> iif(List<ExpressionContext> arguments) {
            if (arguments.size() < 2 || arguments.size() > 3) {
                throw unexpectedNumberOfArguments(arguments.size(), "iif");
            }
            Collection<FHIRPathNode> criterion = visit(arguments.get(0));
            if (!evaluatesToBoolean(criterion) && !criterion.isEmpty()) {
                throw new IllegalArgumentException("'iff' function criterion must evaluate to a boolean or empty");
            }
            // criterion
            if (isTrue(criterion)) {
                // true-result
                return visit(arguments.get(1));
            } else if (arguments.size() == 3) {
                // otherwise-result (optional)
                return visit(arguments.get(2));
            }
            return empty();
        }

        private Collection<FHIRPathNode> is(Collection<ExpressionContext> arguments) {
            if (arguments.size() != 1) {
                throw unexpectedNumberOfArguments(arguments.size(), "is");
            }

            Collection<FHIRPathNode> currentContext = getCurrentContext();
            if (currentContext.isEmpty()) {
                return SINGLETON_FALSE;
            } else if (currentContext.size() > 1) {
                throw new IllegalArgumentException(String.format("Input collection has %d items, but only 1 is allowed", currentContext.size()));
            }

            ExpressionContext typeName = arguments.iterator().next();
            String identifier = typeName.getText().replace("`", "");
            FHIRPathType type = FHIRPathType.from(identifier);
            if (type == null) {
                return SINGLETON_FALSE;
            }
            FHIRPathNode node = getSingleton(currentContext);
            return type.isAssignableFrom(node.type()) ? SINGLETON_TRUE : SINGLETON_FALSE;
        }

        private Collection<FHIRPathNode> ofType(List<ExpressionContext> arguments) {
            if (arguments.size() != 1) {
                throw unexpectedNumberOfArguments(arguments.size(), "ofType");
            }
            Collection<FHIRPathNode> result = new ArrayList<>();
            ExpressionContext typeName = arguments.get(0);
            String identifier = typeName.getText().replace("`", "");
            FHIRPathType type = FHIRPathType.from(identifier);
            if (type == null) {
                throw new IllegalArgumentException(String.format("Argument '%s' cannot be resolved to a valid type identifier", identifier));
            }
            for (FHIRPathNode node : getCurrentContext()) {
                FHIRPathType nodeType = node.type();
                if (SYSTEM_NAMESPACE.equals(type.namespace()) && node.hasValue()) {
                    nodeType = node.getValue().type();
                }
                if (type.isAssignableFrom(nodeType)) {
                    result.add(node);
                }
            }
            return result;
        }

        private Collection<FHIRPathNode> popContext() {
            if (!contextStack.isEmpty()) {
                return contextStack.pop();
            }
            return null;
        }

        private void pushContext(Collection<FHIRPathNode> context) {
            if (context != null) {
                contextStack.push(context);
            }
        }

        private Collection<FHIRPathNode> select(List<ExpressionContext> arguments) {
            if (arguments.size() != 1) {
                throw unexpectedNumberOfArguments(arguments.size(), "select");
            }
            ExpressionContext projection = arguments.get(0);
            Collection<FHIRPathNode> result = new ArrayList<>();
            for (FHIRPathNode node : getCurrentContext()) {
                pushContext(singleton(node));
                result.addAll(visit(projection));
                popContext();
            }
            return result;
        }

        private Collection<FHIRPathNode> trace(List<ExpressionContext> arguments) {
            if (arguments.size() < 1 || arguments.size() > 2) {
                throw unexpectedNumberOfArguments(arguments.size(), "trace");
            }
            String name = getString(visit(arguments.get(0)));
            Collection<FHIRPathNode> currentContext = getCurrentContext();
            Collection<FHIRPathNode> nodes = (arguments.size() == 1) ? currentContext : visit(arguments.get(1));
            if (!nodes.isEmpty()) {
                if (log.isLoggable(Level.FINE)) {
                    log.fine(name + ": " + nodes);
                }
            }
            return currentContext;
        }

        private IllegalArgumentException unexpectedNumberOfArguments(int arity, String functionName) {
            return new IllegalArgumentException(String.format("Unexpected number of arguments: %d for function: '%s'", arity, functionName));
        }

        private Collection<FHIRPathNode> where(List<ExpressionContext> arguments) {
            if (arguments.size() != 1) {
                throw unexpectedNumberOfArguments(arguments.size(), "where");
            }
            ExpressionContext criteria = arguments.get(0);
            Collection<FHIRPathNode> result = new ArrayList<>();
            for (FHIRPathNode node : getCurrentContext()) {
                pushContext(singleton(node));
                if (isTrue(visit(criteria))) {
                    result.add(node);
                }
                popContext();
            }
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitIndexerExpression(FHIRPathParser.IndexerExpressionContext ctx) {
            debug(ctx);
            indentLevel++;

            Collection<FHIRPathNode> result = empty();

            Collection<FHIRPathNode> nodes = visit(ctx.expression(0));

            List<?> list = (nodes instanceof List) ? (List<?>) nodes : new ArrayList<>(nodes);
            int index = getInteger(visit(ctx.expression(1)));

            if (index >= 0 && index < list.size()) {
                result = singleton((FHIRPathNode) list.get(index));
            }

            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitPolarityExpression(FHIRPathParser.PolarityExpressionContext ctx) {
            debug(ctx);
            indentLevel++;

            Collection<FHIRPathNode> nodes = visit(ctx.expression());

            if (!isSingleton(nodes)) {
                indentLevel--;
                return empty();
            }

            Collection<FHIRPathNode> result = empty();

            FHIRPathSystemValue value = getSystemValue(nodes);
            String polarity = ctx.getChild(0).getText();

            if (value.isNumberValue()) {
                switch (polarity) {
                case "+":
                    result = singleton(value.asNumberValue().plus());
                    break;
                case "-":
                    result = singleton(value.asNumberValue().negate());
                    break;
                }
            }

            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitAdditiveExpression(FHIRPathParser.AdditiveExpressionContext ctx) {
            debug(ctx);
            indentLevel++;

            Collection<FHIRPathNode> left = visit(ctx.expression(0));
            Collection<FHIRPathNode> right = visit(ctx.expression(1));

            Collection<FHIRPathNode> result = empty();

            String operator = ctx.getChild(1).getText();

            if ((hasNumberValue(left) && hasNumberValue(right)) || (hasStringValue(left) && hasStringValue(right))) {
                if (hasNumberValue(left) && hasNumberValue(right)) {
                    switch (operator) {
                    case "+":
                        result = singleton(getNumberValue(left).add(getNumberValue(right)));
                        break;
                    case "-":
                        result = singleton(getNumberValue(left).asNumberValue().subtract(getNumberValue(right)));
                        break;
                    }
                } else if (hasStringValue(left) && hasStringValue(right)) {
                    if ("+".equals(operator) || "&".equals(operator)) {
                        // concatenation
                        result = singleton(getStringValue(left).concat(getStringValue(right)));
                    } else {
                        throw new IllegalArgumentException("Invalid argument(s) for '" + operator + "' operator");
                    }
                }
            } else if (((hasStringValue(left) && right.isEmpty()) || (left.isEmpty() && hasStringValue(right))) && ("+".equals(operator) || "&".equals(operator))) {
                if ("&".equals(operator)) {
                    // concatenation where an empty collection is treated as an empty string
                    if (hasStringValue(left) && right.isEmpty()) {
                        FHIRPathStringValue leftValue = getStringValue(left);
                        result = singleton(leftValue.asStringValue().concat(EMPTY_STRING));
                    } else if (left.isEmpty() && hasStringValue(right)) {
                        FHIRPathStringValue rightValue = getStringValue(right);
                        result = singleton(EMPTY_STRING.concat(rightValue.asStringValue()));
                    } else if (left.isEmpty() && right.isEmpty()) {
                        result = singleton(EMPTY_STRING);
                    }
                }
            } else if (hasQuantityValue(left) && hasQuantityValue(right)) {
                FHIRPathQuantityValue leftValue = getQuantityValue(left);
                FHIRPathQuantityValue rightValue = getQuantityValue(right);
                switch (operator) {
                case "+":
                    result = singleton(leftValue.add(rightValue));
                    break;
                case "-":
                    result = singleton(leftValue.subtract(rightValue));
                    break;
                }
            } else if ((hasTemporalValue(left) && hasQuantityValue(right)) ||
                    (hasQuantityValue(left) && hasTemporalValue(right))) {
                FHIRPathTemporalValue temporalValue = hasTemporalValue(left) ? getTemporalValue(left) : getTemporalValue(right);
                FHIRPathQuantityValue quantityValue = hasQuantityValue(left) ? getQuantityValue(left) : getQuantityValue(right);
                switch (operator) {
                case "+":
                    result = singleton(temporalValue.add(quantityValue));
                    break;
                case "-":
                    result = singleton(temporalValue.subtract(quantityValue));
                    break;
                }
            } else if (isQuantityNode(left) && isQuantityNode(right)) {
                FHIRPathQuantityNode leftNode = getQuantityNode(left);
                FHIRPathQuantityNode rightNode = getQuantityNode(right);
                switch(operator) {
                case "+":
                    result = singleton(leftNode.add(rightNode));
                    break;
                case "-":
                    result = singleton(leftNode.subtract(rightNode));
                    break;
                }
            } else if (!left.isEmpty() && !right.isEmpty()){
                throw new IllegalArgumentException("Invalid argument(s) for '" + operator + "' operator");
            }

            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitMultiplicativeExpression(FHIRPathParser.MultiplicativeExpressionContext ctx) {
            debug(ctx);
            indentLevel++;

            Collection<FHIRPathNode> left = visit(ctx.expression(0));
            Collection<FHIRPathNode> right = visit(ctx.expression(1));

            if (!hasSystemValue(left) || !hasSystemValue(right)) {
                indentLevel--;
                return empty();
            }

            Collection<FHIRPathNode> result = empty();

            FHIRPathSystemValue leftValue = getSystemValue(left);
            FHIRPathSystemValue rightValue = getSystemValue(right);

            String operator = ctx.getChild(1).getText();

            if (leftValue.isNumberValue() && rightValue.isNumberValue()) {
                try {
                    switch (operator) {
                    case "*":
                        result = singleton(leftValue.asNumberValue().multiply(rightValue.asNumberValue()));
                        break;
                    case "/":
                        result = singleton(leftValue.asNumberValue().divide(rightValue.asNumberValue()));
                        break;
                    case "div":
                        result = singleton(leftValue.asNumberValue().div(rightValue.asNumberValue()));
                        break;
                    case "mod":
                        result = singleton(leftValue.asNumberValue().mod(rightValue.asNumberValue()));
                        break;
                    }
                } catch (ArithmeticException e) {
                    // TODO: log this
                }
            }

            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitUnionExpression(FHIRPathParser.UnionExpressionContext ctx) {
            debug(ctx);
            indentLevel++;

            Collection<FHIRPathNode> left = visit(ctx.expression(0));
            Collection<FHIRPathNode> right = visit(ctx.expression(1));

            Set<FHIRPathNode> union = new LinkedHashSet<>(left);
            union.addAll(right);

            indentLevel--;
            return new ArrayList<>(union);
        }

        @Override
        public Collection<FHIRPathNode> visitOrExpression(FHIRPathParser.OrExpressionContext ctx) {
            debug(ctx);
            indentLevel++;

            Collection<FHIRPathNode> result = empty();

            // evaluate left operand
            Collection<FHIRPathNode> left = visit(ctx.expression(0));

            String operator = ctx.getChild(1).getText();

            switch (operator) {
            case "or":
                // Returns false if both operands evaluate to false, true if either operand evaluates to true, and empty ({ }) otherwise:
                if (evaluatesToBoolean(left) && isTrue(left)) {
                    // short-circuit evaluation
                    result = SINGLETON_TRUE;
                } else {
                    // evaluate right operand
                    Collection<FHIRPathNode> right = visit(ctx.expression(1));
                    if (evaluatesToBoolean(right) && isTrue(right)) {
                        result = SINGLETON_TRUE;
                    } else if (evaluatesToBoolean(left) && evaluatesToBoolean(right) &&
                            isFalse(left) && isFalse(right)) {
                        result = SINGLETON_FALSE;
                    }
                }
                break;
            case "xor":
                // evaluate right operand
                Collection<FHIRPathNode> right = visit(ctx.expression(1));

                // Returns true if exactly one of the operands evaluates to true, false if either both operands evaluate to true or both operands evaluate to false, and the empty collection ({ }) otherwise:
                if (evaluatesToBoolean(left) && evaluatesToBoolean(right)) {
                    result = ((isTrue(left) || isTrue(right)) && !(isTrue(left) && isTrue(right))) ? SINGLETON_TRUE : SINGLETON_FALSE;
                }
                break;
            }

            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitAndExpression(FHIRPathParser.AndExpressionContext ctx) {
            debug(ctx);
            indentLevel++;

            Collection<FHIRPathNode> result = empty();

            // evaluate left operand
            Collection<FHIRPathNode> left = visit(ctx.expression(0));

            // Returns true if both operands evaluate to true, false if either operand evaluates to false, and the empty collection ({ }) otherwise.
            if (evaluatesToBoolean(left) && isFalse(left)) {
                // short-circuit evaluation
                result = SINGLETON_FALSE;
            } else {
                // evaluate right operand
                Collection<FHIRPathNode> right = visit(ctx.expression(1));
                if (evaluatesToBoolean(right) && isFalse(right)) {
                    result = SINGLETON_FALSE;
                } else if (evaluatesToBoolean(left) && evaluatesToBoolean(right) &&
                        isTrue(left) && isTrue(right)) {
                    result = SINGLETON_TRUE;
                }
            }

            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitMembershipExpression(FHIRPathParser.MembershipExpressionContext ctx) {
            debug(ctx);
            indentLevel++;

            Collection<FHIRPathNode> result = SINGLETON_FALSE;

            Collection<FHIRPathNode> left = visit(ctx.expression(0));
            Collection<FHIRPathNode> right = visit(ctx.expression(1));

            String operator = ctx.getChild(1).getText();

            switch (operator) {
            case "in":
                if ((isCodedElementNode(left) || isStringElementNode(left) || isUriElementNode(left)) && isStringValue(right)) {
                    // For backwards compatibility per: https://jira.hl7.org/projects/FHIR/issues/FHIR-26605
                    FHIRPathFunction memberOfFunction = FHIRPathFunction.registry().getFunction("memberOf");
                    result = memberOfFunction.apply(evaluationContext, left, Collections.singletonList(right));
                } else if (right.containsAll(left)) {
                    result = SINGLETON_TRUE;
                }
                break;
            case "contains":
                if (left.containsAll(right)) {
                    result = SINGLETON_TRUE;
                }
                break;
            }

            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitInequalityExpression(FHIRPathParser.InequalityExpressionContext ctx) {
            debug(ctx);
            indentLevel++;

            Collection<FHIRPathNode> left = visit(ctx.expression(0));
            Collection<FHIRPathNode> right = visit(ctx.expression(1));

            if (!isSingleton(left) || !isSingleton(right)) {
                indentLevel--;
                return SINGLETON_FALSE;
            }

            Collection<FHIRPathNode> result = SINGLETON_FALSE;

            FHIRPathNode leftNode = getSingleton(left);
            FHIRPathNode rightNode = getSingleton(right);

            if (hasSystemValue(leftNode) && hasSystemValue(rightNode) &&
                    !isTypeCompatible(getSystemValue(leftNode), getSystemValue(rightNode))) {
                throw new IllegalArgumentException("Type: '" + leftNode.type().getName() + "' is not compatible with type: '" + rightNode.type().getName() + "'");
            }

            String operator = ctx.getChild(1).getText();

            if (leftNode.isComparableTo(rightNode)) {
                switch (operator) {
                case "<=":
                    if (leftNode.compareTo(rightNode) <= 0) {
                        result = SINGLETON_TRUE;
                    }
                    break;
                case "<":
                    if (leftNode.compareTo(rightNode) < 0) {
                        result = SINGLETON_TRUE;
                    }
                    break;
                case ">":
                    if (leftNode.compareTo(rightNode) > 0) {
                        result = SINGLETON_TRUE;
                    }
                    break;
                case ">=":
                    if (leftNode.compareTo(rightNode) >= 0) {
                        result = SINGLETON_TRUE;
                    }
                    break;
                }
            } else {
                result = empty();
            }

            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitInvocationExpression(FHIRPathParser.InvocationExpressionContext ctx) {
            debug(ctx);
            indentLevel++;

            pushContext(visit(ctx.expression()));
            Collection<FHIRPathNode> result = visit(ctx.invocation());
            popContext();

            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitEqualityExpression(FHIRPathParser.EqualityExpressionContext ctx) {
            debug(ctx);
            indentLevel++;

            Collection<FHIRPathNode> result = SINGLETON_FALSE;

            Collection<FHIRPathNode> left = visit(ctx.expression(0));
            Collection<FHIRPathNode> right = visit(ctx.expression(1));

            if (left.isEmpty() || right.isEmpty()) {
                indentLevel--;
                return empty();
            }

            if (left.size() != right.size()) {
                indentLevel--;
                return SINGLETON_FALSE;
            }

            if (!isComparableTo(left, right)) {
                indentLevel--;
                return empty();
            }

            String operator = ctx.getChild(1).getText();

            // TODO: "equals" and "equivalent" have different semantics
            switch (operator) {
            case "=":
            case "~":
                if (left.equals(right)) {
                    result = SINGLETON_TRUE;
                }
                break;
            case "!=":
            case "!~":
                if (!left.equals(right)) {
                    result = SINGLETON_TRUE;
                }
                break;
            }

            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitImpliesExpression(FHIRPathParser.ImpliesExpressionContext ctx) {
            debug(ctx);
            indentLevel++;

            Collection<FHIRPathNode> result = empty();

            Collection<FHIRPathNode> left = visit(ctx.expression(0));
            Collection<FHIRPathNode> right = visit(ctx.expression(1));

            // If the left operand evaluates to true, this operator returns the boolean evaluation of the right operand. If the left operand evaluates to false, this operator returns true. Otherwise, this operator returns true if the right operand evaluates to true, and the empty collection ({ }) otherwise.
            if (evaluatesToBoolean(left) && evaluatesToBoolean(right)) {
                // !left || right
                result = (!isTrue(left) || isTrue(right)) ? SINGLETON_TRUE : SINGLETON_FALSE;
            } else if ((left.isEmpty() && evaluatesToBoolean(right) && isTrue(right)) ||
                    (evaluatesToBoolean(left) && isFalse(left) && right.isEmpty())) {
                result = SINGLETON_TRUE;
            }

            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitTermExpression(FHIRPathParser.TermExpressionContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitTypeExpression(FHIRPathParser.TypeExpressionContext ctx) {
            debug(ctx);
            indentLevel++;

            Collection<FHIRPathNode> nodes = visit(ctx.expression());

            String operator = ctx.getChild(1).getText();

            Collection<FHIRPathNode> result = "is".equals(operator) ? SINGLETON_FALSE : new ArrayList<>();

            String qualifiedIdentifier = getString(visit(ctx.typeSpecifier()));
            FHIRPathType type = FHIRPathType.from(qualifiedIdentifier);
            if (type == null) {
                throw new IllegalArgumentException(String.format("Argument '%s' cannot be resolved to a valid type identifier", qualifiedIdentifier));
            }

            switch (operator) {
            case "is":
                if (nodes.size() > 1) {
                    throw new IllegalArgumentException(String.format("Input collection has %d items, but only 1 is allowed", nodes.size()));
                } else if (!nodes.isEmpty()) {
                    FHIRPathNode node = getSingleton(nodes);
                    if (type.isAssignableFrom(node.type())) {
                        result = SINGLETON_TRUE;
                    }
                }
                break;
            case "as":
                for (FHIRPathNode node : nodes) {
                    if (type.isAssignableFrom(node.type())) {
                        result.add(node);
                    }
                }
                break;
            }

            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitInvocationTerm(FHIRPathParser.InvocationTermContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitLiteralTerm(FHIRPathParser.LiteralTermContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = LITERAL_CACHE.computeIfAbsent(ctx.getText(), t -> visitChildren(ctx));
            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitExternalConstantTerm(FHIRPathParser.ExternalConstantTermContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitParenthesizedTerm(FHIRPathParser.ParenthesizedTermContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visit(ctx.expression());
            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitNullLiteral(FHIRPathParser.NullLiteralContext ctx) {
            debug(ctx);
            return empty();
        }

        @Override
        public Collection<FHIRPathNode> visitBooleanLiteral(FHIRPathParser.BooleanLiteralContext ctx) {
            debug(ctx);
            Boolean _boolean = Boolean.valueOf(ctx.getText());
            return _boolean ? SINGLETON_TRUE : SINGLETON_FALSE;
        }

        @Override
        public Collection<FHIRPathNode> visitStringLiteral(FHIRPathParser.StringLiteralContext ctx) {
            debug(ctx);
            String text = unescape(ctx.getText());
            return singleton(stringValue(text.substring(1, text.length() - 1)));
        }

        @Override
        public Collection<FHIRPathNode> visitNumberLiteral(FHIRPathParser.NumberLiteralContext ctx) {
            debug(ctx);
            String text = ctx.getText();
            if (text.contains(".")) {
                return singleton(decimalValue(new BigDecimal(text)));
            } else {
                return singleton(integerValue(Integer.parseInt(text)));
            }
        }

        @Override
        public Collection<FHIRPathNode> visitDateLiteral(FHIRPathParser.DateLiteralContext ctx) {
            debug(ctx);
            return singleton(FHIRPathDateValue.dateValue(ctx.getText().substring(1)));
        }

        @Override
        public Collection<FHIRPathNode> visitDateTimeLiteral(FHIRPathParser.DateTimeLiteralContext ctx) {
            debug(ctx);
            return singleton(FHIRPathDateTimeValue.dateTimeValue(ctx.getText().substring(1)));
        }

        @Override
        public Collection<FHIRPathNode> visitTimeLiteral(FHIRPathParser.TimeLiteralContext ctx) {
            debug(ctx);
            return singleton(FHIRPathTimeValue.timeValue(ctx.getText().substring(1)));
        }

        @Override
        public Collection<FHIRPathNode> visitQuantityLiteral(FHIRPathParser.QuantityLiteralContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitExternalConstant(FHIRPathParser.ExternalConstantContext ctx) {
            debug(ctx);
            indentLevel++;
            String identifier = getString(visit(ctx.identifier()));
            indentLevel--;
            return evaluationContext.getExternalConstant(identifier);
        }

        @Override
        public Collection<FHIRPathNode> visitMemberInvocation(FHIRPathParser.MemberInvocationContext ctx) {
            debug(ctx);
            indentLevel++;

            Collection<FHIRPathNode> currentContext = getCurrentContext();
            String identifier = getString(visit(ctx.identifier()));

            if (isSingleton(currentContext)) {
                FHIRPathNode node = getSingleton(currentContext);
                if (closure(node.type()).contains(identifier)) {
                    indentLevel--;
                    return currentContext;
                }
            }

            Collection<FHIRPathNode> result = currentContext.stream()
                    .flatMap(node -> node.children().stream())
                    .filter(node -> identifier.equals(node.name()))
                    .collect(Collectors.toList());

            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitFunctionInvocation(FHIRPathParser.FunctionInvocationContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitThisInvocation(FHIRPathParser.ThisInvocationContext ctx) {
            debug(ctx);
            return getCurrentContext();
        }

        @Override
        public Collection<FHIRPathNode> visitIndexInvocation(FHIRPathParser.IndexInvocationContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitTotalInvocation(FHIRPathParser.TotalInvocationContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitFunction(FHIRPathParser.FunctionContext ctx) {
            debug(ctx);
            indentLevel++;

            Collection<FHIRPathNode> result = empty();

            String functionName = getString(visit(ctx.identifier()));

            List<ExpressionContext> arguments = new ArrayList<ExpressionContext>();
            ParamListContext paramList = ctx.paramList();
            if (paramList != null) {
                arguments.addAll(ctx.paramList().expression());
            }

            Collection<FHIRPathNode> currentContext = getCurrentContext();

            switch (functionName) {
            case "all":
                result = all(arguments);
                break;
            case "as":
                result = as(arguments);
                break;
            case "exists":
                result = exists(arguments);
                break;
            case "iif":
                result = iif(arguments);
                break;
            case "is":
                result = is(arguments);
                break;
            case "ofType":
                result = ofType(arguments);
                break;
            case "select":
                result = select(arguments);
                break;
            case "trace":
                result = trace(arguments);
                break;
            case "where":
                result = where(arguments);
                break;
            default:
                FHIRPathFunction function = FHIRPathFunction.registry().getFunction(functionName);
                if (function == null) {
                    throw new IllegalArgumentException("Function: '" + functionName + "' not found");
                }
                if (arguments.size() < function.getMinArity() || arguments.size() > function.getMaxArity()) {
                    throw unexpectedNumberOfArguments(arguments.size(), functionName);
                }
                result = function.apply(evaluationContext, currentContext, arguments.stream()
                    // evaluate arguments: ExpressionContext -> Collection<FHIRPathNode>
                    .map(expressionContext -> visit(expressionContext))
                    .collect(Collectors.toList()));
                break;
            }

            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitParamList(FHIRPathParser.ParamListContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitQuantity(FHIRPathParser.QuantityContext ctx) {
            debug(ctx);
            indentLevel++;
            String number = ctx.NUMBER().getText();
            String text = ctx.unit().getText();
            String unit = text.substring(1, text.length() - 1);
            indentLevel--;
            return singleton(FHIRPathQuantityValue.quantityValue(new BigDecimal(number), unit));
        }

        @Override
        public Collection<FHIRPathNode> visitUnit(FHIRPathParser.UnitContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitDateTimePrecision(FHIRPathParser.DateTimePrecisionContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitPluralDateTimePrecision(FHIRPathParser.PluralDateTimePrecisionContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitTypeSpecifier(FHIRPathParser.TypeSpecifierContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }

        @Override
        public Collection<FHIRPathNode> visitQualifiedIdentifier(FHIRPathParser.QualifiedIdentifierContext ctx) {
            debug(ctx);
            String text = ctx.getText().replace("`", "");
            return singleton(stringValue(text));
        }

        @Override
        public Collection<FHIRPathNode> visitIdentifier(FHIRPathParser.IdentifierContext ctx) {
            debug(ctx);
            String text = ctx.getText();
            Collection<FHIRPathNode> result = IDENTIFIER_CACHE.computeIfAbsent(text, t -> singleton(stringValue(text.startsWith("`") ? text.substring(1, text.length() - 1) : text)));
            return result;
        }

        private String indent() {
            StringBuilder builder = new StringBuilder();
            for (int i = 0;i < indentLevel; i++) {
                builder.append("    ");
            }
            return builder.toString();
        }

        private void debug(ParseTree ctx) {
            if (log.isLoggable(Level.FINE)) {
                log.fine(indent() + ctx.getClass().getSimpleName() + ": " + ctx.getText() + ", childCount: " + ctx.getChildCount());
            }
        }
    }

    /**
     * A context object used to pass information to/from the FHIRPath evaluation engine
     */
    public static class EvaluationContext {
        private static final String UCUM_SYSTEM = "http://unitsofmeasure.org";
        private static final Collection<FHIRPathNode> UCUM_SYSTEM_SINGLETON = singleton(stringValue(UCUM_SYSTEM));

        private static final String LOINC_SYSTEM = "http://loinc.org";
        private static final Collection<FHIRPathNode> LOINC_SYSTEM_SINGLETON = singleton(stringValue(LOINC_SYSTEM));

        private static final String SCT_SYSTEM = "http://snomed.info/sct";
        private static final Collection<FHIRPathNode> SCT_SYSTEM_SINGLETON = singleton(stringValue(SCT_SYSTEM));

        private final FHIRPathTree tree;
        private final Map<String, Collection<FHIRPathNode>> externalConstantMap = new HashMap<>();

        private Constraint constraint;
        private final List<Issue> issues = new ArrayList<>();

        /**
         * Create an empty evaluation context, evaluating stand-alone expressions
         */
        public EvaluationContext() {
            this((FHIRPathTree) null);
        }

        /**
         * Create an evaluation context where the passed resource is the context root.
         * Sets %resource and %rootResource external constants to the passed resource, but these can be overridden.
         *
         * @param resource
         *     the resource
         */
        public EvaluationContext(Resource resource) {
            this(FHIRPathTree.tree(resource));
            externalConstantMap.put("rootResource", singleton(tree.getRoot()));
            externalConstantMap.put("resource", singleton(tree.getRoot()));
        }

        /**
         * Create an evaluation context where the passed element is the context root.
         *
         * @param element
         *     the element
         */
        public EvaluationContext(Element element) {
            this(FHIRPathTree.tree(element));
        }

        private EvaluationContext(FHIRPathTree tree) {
            this.tree = tree;
        }

        /**
         * Get the FHIRPath tree associated with this EvaluationContext
         *
         * @return
         *     the FHIRPath tree associated with this EvaluationContext
         */
        public FHIRPathTree getTree() {
            return tree;
        }

        /**
         * Set an external constant using a name and FHIRPath node
         *
         * @param name
         *     the name
         * @param node
         *     the FHIRPath node
         */
        public void setExternalConstant(String name, FHIRPathNode node) {
            externalConstantMap.put(name, singleton(node));
        }

        /**
         * Set an external constant using a name and a collection of FHIRPath node
         *
         * @param name
         *     the name
         * @param nodes
         *     the collection of FHIRPath nodes
         */
        public void setExternalConstant(String name, Collection<FHIRPathNode> nodes) {
            externalConstantMap.put(name, nodes);
        }

        /**
         * Unset an external constant with the given name
         *
         * @param name
         *     the name
         */
        public void unsetExternalConstant(String name) {
            externalConstantMap.remove(name);
        }

        /**
         * Get an external constant with the given name
         *
         * @param name
         *     the name
         * @return
         *     the external constant as a collection of FHIRPath nodes or an empty collection
         */
        public Collection<FHIRPathNode> getExternalConstant(String name) {
            switch (name) {
            case "ucum":
                return UCUM_SYSTEM_SINGLETON;
            case "loinc":
                return LOINC_SYSTEM_SINGLETON;
            case "sct":
                return SCT_SYSTEM_SINGLETON;
            default:
                if (name.startsWith("ext-")) {
                    return singleton(stringValue(name.replace("ext-", "http://hl7.org/fhir/StructureDefinition/")));
                }
                if (name.startsWith("vs-")) {
                    return singleton(stringValue(name.replace("vs-", "http://hl7.org/fhir/ValueSet/")));
                }
                return externalConstantMap.getOrDefault(name, empty());
            }
        }

        /**
         * Indicates whether this EvaluationContext has an external constant with the given name
         *
         * @param name
         *     the name
         * @return
         *     true if this EvaluationContext has an external constant with the given name, otherwise false
         */
        public boolean hasExternalConstant(String name) {
            return externalConstantMap.containsKey(name);
        }

        /**
         * Set the constraint currently under evaluation
         *
         * <p>If a {@link Constraint} is the source of the expression under evaluation, then this method allows the
         * client to make it available to the evaluation engine to access additional information about the constraint
         * (e.g. id, level, location, description, etc.)
         *
         * @param constraint
         *     the constraint currently under evaluation
         */
        public void setConstraint(Constraint constraint) {
            this.constraint = constraint;
        }

        /**
         * Unset the constraint currently under evaluation
         */
        public void unsetConstraint() {
            constraint = null;
        }

        /**
         * Get the constraint currently under evaluation
         *
         * @return
         *     the constraint currently under evaluation if exists, otherwise null
         */
        public Constraint getConstraint() {
            return constraint;
        }

        /**
         * Indicates whether this evaluation context has an associated constraint
         *
         * @return
         *     true if this evaluation context has an associated constraint, otherwise false
         */
        public boolean hasConstraint() {
            return constraint != null;
        }

        /**
         * Get the list of supplemental issues that were generated during evaluation
         *
         * <p>Supplemental issues are used to convey additional information about the evaluation to the client
         *
         * @return
         *     the list of supplemental issues that were generated during evaluation
         */
        public List<Issue> getIssues() {
            return issues;
        }

        /**
         * Clear the list of supplemental issues that were generated during evaluation
         */
        public void clearIssues() {
            issues.clear();
        }

        /**
         * Indicates whether this evaluation context has supplemental issues that were generated during evaluation
         *
         * @return
         *     true if this evaluation context has supplemental issues that were generated during evaluation, otherwise false
         */
        public boolean hasIssues() {
            return !issues.isEmpty();
        }
    }
}