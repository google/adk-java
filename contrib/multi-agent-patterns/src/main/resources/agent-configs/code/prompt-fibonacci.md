## Prompt: 509. Fibonacci Number
Solve the Fibnacci problem.
Write code in Java and convert to Python:

The Fibonacci numbers, commonly denoted F(n) form a sequence, 
called the Fibonacci sequence, such that each number is 
the sum of the two preceding ones, starting from 0 and 1. That is,
F(0) = 0, F(1) = 1
F(n) = F(n - 1) + F(n - 2), for n > 1.
Given n, calculate F(n).

Constraints:
0 <= n <= 30

## Response
Here is the Java code for the Fibonacci problem and its conversion to Python:

# JAVA
```
    /**
     * Calculates the nth Fibonacci number.
     * The Fibonacci numbers, commonly denoted F(n) form a sequence,
     * called the Fibonacci sequence, such that each number is
     * the sum of the two preceding ones, starting from 0 and 1.
     * F(0) = 0, F(1) = 1
     * F(n) = F(n - 1) + F(n - 2), for n > 1.
     *
     * @param n The index of the Fibonacci number to calculate.
     * @return The nth Fibonacci number.
     * @throws IllegalArgumentException if n is less than 0 or greater than 30.
     */
     
public class Solution {
    public int fib(int n) {
        // Constraints: 0 <= n <= 30
        if (n < 0 || n > 30) {
            throw new IllegalArgumentException("Input n must be between 0 and 30, inclusive.");
        }

        // Base cases
        if (n == 0) {
            return 0;
        }
        if (n == 1) {
            return 1;
        }

        // Iterative approach to calculate Fibonacci numbers
        int a = 0; // Represents F(i-2)
        int b = 1; // Represents F(i-1)
        int result = 0; // Represents F(i)

        // Start from i = 2, as F(0) and F(1) are already handled
        for (int i = 2; i <= n; i++) {
            result = a + b; // F(i) = F(i-2) + F(i-1)
            a = b;          // Update F(i-2) to be the previous F(i-1)
            b = result;     // Update F(i-1) to be the current F(i)
        }

        return result;
    }
}
```
# PYTHON 
```
class Solution:
    """
    Calculates the nth Fibonacci number.
    The Fibonacci numbers, commonly denoted F(n) form a sequence,
    called the Fibonacci sequence, such that each number is
    the sum of the two preceding ones, starting from 0 and 1.
    F(0) = 0, F(1) = 1
    F(n) = F(n - 1) + F(n - 2), for n > 1.
    """
    def fib(self, n: int) -> int:
        # Constraints: 0 <= n <= 30
        if not (0 <= n <= 30):
            raise ValueError("Input n must be between 0 and 30, inclusive.")

        # Base cases
        if n == 0:
            return 0
        if n == 1:
            return 1

        # Iterative approach to calculate Fibonacci numbers
        a = 0  # Represents F(i-2)
        b = 1  # Represents F(i-1)
        result = 0 # Represents F(i)

        # Start from i = 2, as F(0) and F(1) are already handled
        for _ in range(2, n + 1):
            result = a + b  # F(i) = F(i-2) + F(i-1)
            a = b           # Update F(i-2) to be the previous F(i-1)
            b = result      # Update F(i-1) to be the current F(i)

        return result
```