# Contributing to LLM Token Optimizer

Thank you for your interest in contributing! This document provides guidelines for contributing to the project.

## 🎯 Ways to Contribute

- **Bug Reports**: Report bugs via GitHub Issues
- **Feature Requests**: Suggest new features or improvements
- **Code Contributions**: Submit pull requests with bug fixes or new features
- **Documentation**: Improve README, code comments, or add examples
- **Testing**: Add unit tests or integration tests

## 🚀 Getting Started

1. **Fork the repository** on GitHub
2. **Clone your fork** locally:
   ```bash
   git clone https://github.com/nikbarse1/demo-for-llm.git
   cd demo-for-llm
   ```
3. **Create a branch** for your changes:
   ```bash
   git checkout -b feature/your-feature-name
   ```

## 💻 Development Setup

### Prerequisites
- Java 21+
- Maven 3.9+
- Git
- Your favorite IDE (IntelliJ IDEA, Eclipse, VS Code)

### Build and Test
```bash
# Build the project
mvn clean install

# Run tests
mvn test

# Run the application
mvn spring-boot:run
```

## 📝 Code Guidelines

### Java Code Style
- Follow standard Java naming conventions
- Use meaningful variable and method names
- Keep methods focused and concise (< 50 lines)
- Add JavaDoc for public methods and classes
- Use Lombok annotations to reduce boilerplate

### Example:
```java
/**
 * Counts tokens in the provided text using OpenAI's tokenization algorithm.
 * 
 * @param text The text to count tokens for
 * @return The number of tokens
 */
public int countTokens(String text) {
    if (text == null || text.isBlank()) {
        return 0;
    }
    return encoding.countTokens(text);
}
```

### Testing
- Write unit tests for all new features
- Aim for >80% code coverage
- Use descriptive test method names
- Follow AAA pattern: Arrange, Act, Assert

### Example:
```java
@Test
void testCountTokens_withValidText() {
    // Arrange
    String text = "Hello, world!";
    
    // Act
    int tokenCount = tokenService.countTokens(text);
    
    // Assert
    assertTrue(tokenCount > 0);
}
```

## 🔄 Pull Request Process

1. **Update your branch** with the latest main:
   ```bash
   git checkout main
   git pull upstream main
   git checkout your-feature-branch
   git rebase main
   ```

2. **Ensure all tests pass**:
   ```bash
   mvn clean test
   ```

3. **Commit your changes** with clear messages:
   ```bash
   git commit -m "Add feature: token cost calculator"
   ```

4. **Push to your fork**:
   ```bash
   git push origin feature/your-feature-name
   ```

5. **Create a Pull Request** on GitHub with:
   - Clear title describing the change
   - Detailed description of what and why
   - Reference to any related issues
   - Screenshots (if UI changes)

## 📋 Commit Message Guidelines

Use conventional commit format:

```
type(scope): subject

body (optional)

footer (optional)
```

### Types:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, etc.)
- `refactor`: Code refactoring
- `test`: Adding or updating tests
- `chore`: Maintenance tasks

### Examples:
```
feat(api): add batch token counting endpoint

fix(summarization): handle null responses from LLM API

docs(readme): add Docker deployment instructions

test(controller): add integration tests for optimization endpoint
```

## 🐛 Reporting Bugs

When reporting bugs, please include:

1. **Description**: Clear description of the bug
2. **Steps to Reproduce**: Detailed steps to reproduce the issue
3. **Expected Behavior**: What you expected to happen
4. **Actual Behavior**: What actually happened
5. **Environment**: 
   - OS (Windows, macOS, Linux)
   - Java version
   - Spring Boot version
6. **Logs**: Relevant error messages or stack traces
7. **Screenshots**: If applicable

### Bug Report Template:
```markdown
**Description**
Brief description of the bug

**Steps to Reproduce**
1. Step one
2. Step two
3. Step three

**Expected Behavior**
What should happen

**Actual Behavior**
What actually happens

**Environment**
- OS: Windows 11
- Java: 21.0.1
- Spring Boot: 4.1.0

**Logs**
```
Error logs here
```

**Screenshots**
[Attach screenshots if applicable]
```

## 💡 Feature Requests

When suggesting features, please include:

1. **Use Case**: Why is this feature needed?
2. **Proposed Solution**: How should it work?
3. **Alternatives**: Other solutions you've considered
4. **Additional Context**: Any other relevant information

## ✅ Code Review Process

All submissions require review. We aim to:

- Review PRs within 2-3 business days
- Provide constructive feedback
- Ensure code quality and test coverage
- Maintain project consistency

## 📜 Code of Conduct

### Our Standards

- Be respectful and inclusive
- Welcome newcomers and help them learn
- Focus on constructive criticism
- Respect differing viewpoints
- Show empathy towards others

### Unacceptable Behavior

- Harassment or discriminatory language
- Trolling or insulting comments
- Personal or political attacks
- Publishing others' private information

## 🎓 Learning Resources

If you're new to:

- **Spring Boot**: [Official Documentation](https://spring.io/projects/spring-boot)
- **Java**: [Oracle Java Tutorials](https://docs.oracle.com/javase/tutorial/)
- **Maven**: [Maven Getting Started](https://maven.apache.org/guides/getting-started/)
- **Git**: [Git Handbook](https://guides.github.com/introduction/git-handbook/)

## 🙋 Questions?

If you have questions:

1. Check existing [Issues](https://github.com/yourusername/demo-for-llm/issues)
2. Check the [README](README.md)
3. Open a new issue with the `question` label

## 📞 Contact

For urgent matters or private concerns, please contact the maintainers directly.

---

Thank you for contributing to LLM Token Optimizer! 🎉
