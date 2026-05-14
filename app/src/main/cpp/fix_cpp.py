import sys

with open('native-lib.cpp', 'r') as f:
    content = f.read()

# Fix narrowing error: char -> unsigned char and cast in NewStringUTF
content = content.replace('char s[] = {', 'unsigned char s[] = {')
content = content.replace('return env->NewStringUTF(s);', 'return env->NewStringUTF((char*)s);')

# Fix the literal \n and any other syntax errors at the end
# Based on the view, line 774 was '\n' literal.
content = content.replace('\\n\nextern "C"', '\nextern "C"')

with open('native-lib.cpp', 'w') as f:
    f.write(content)
