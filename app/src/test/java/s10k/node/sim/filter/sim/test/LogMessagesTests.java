/* ==================================================================
 * LogMessagesTests.java - 20 Oct 2026 7:18:40 pm
 *
 * Copyright 2026 SolarNetwork.net Dev Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ==================================================================
 */

package s10k.node.sim.filter.sim.test;

import static org.assertj.core.api.BDDAssertions.then;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.sim.LogMessages;

/**
 * Test cases for the {@link LogMessages} class.
 *
 * @author matt
 * @version 1.0
 */
public class LogMessagesTests {

	private static final String ERROR = "EL1030E: The operator 'ADD' is not supported between objects "
			+ "of type 'java.lang.Integer' and 'null'";

	private static String expressionError(String expression, String root) {
		return "Error evaluating service [Expr] datum property [watts] expression `%s`: %s\n\nExpression root: %s"
				.formatted(expression, ERROR, root);
	}

	@Test
	public void shorten_short() {
		// GIVEN
		String msg = expressionError("a + b", "{a=1}");

		// WHEN
		String result = LogMessages.shorten(msg);

		// THEN
		// @formatter:off
		then(result)
			.as("A short message is unchanged")
			.isEqualTo(msg)
			;
		// @formatter:on
	}

	@Test
	public void shorten_longExpressionAndRoot() {
		// GIVEN
		String expr = "a + b ".repeat(100);
		String root = "{a=1} ".repeat(100);
		String msg = expressionError(expr, root);

		// WHEN
		String result = LogMessages.shorten(msg);

		// THEN
		// @formatter:off
		then(result)
			.as("The expression is shortened inside its quotes")
			.contains("`" + expr.substring(0, LogMessages.MAX_QUOTED_LENGTH) + "…`")
			.as("The error is kept whole")
			.contains("`: " + ERROR + "\n\nExpression root: ")
			.as("The root is shortened")
			.endsWith("Expression root: " + root.substring(0, LogMessages.MAX_ROOT_LENGTH) + "…")
			;
		// @formatter:on
	}

	@Test
	public void shorten_longMessage() {
		// GIVEN
		String msg = "x".repeat(LogMessages.MAX_LENGTH + 10);

		// WHEN
		String result = LogMessages.shorten(msg);

		// THEN
		// @formatter:off
		then(result)
			.as("Other long messages are cut off")
			.hasSize(LogMessages.MAX_LENGTH + 1)
			.endsWith("x…")
			;
		// @formatter:on
	}

	@Test
	public void problem_withoutRoot() {
		// GIVEN
		String msg1 = expressionError("a + b", "{a=1}");
		String msg2 = expressionError("a + b", "{a=2}");

		// WHEN
		String problem1 = LogMessages.problem(msg1);
		String problem2 = LogMessages.problem(msg2);

		// THEN
		// @formatter:off
		then(problem1)
			.as("The problem leaves out the expression root")
			.endsWith("`: " + ERROR)
			.as("Errors with different roots are the same problem")
			.isEqualTo(problem2)
			;
		then(LogMessages.problem("Something else"))
			.as("A message without a root is the problem")
			.isEqualTo("Something else")
			;
		// @formatter:on
	}

}
