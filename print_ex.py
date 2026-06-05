import re

with open('app/src/main/java/com/doubleangels/redact/media/FormatConverter.java', 'r') as f:
    content = f.read()

content = content.replace('com.doubleangels.redact.sentry.SentryManager.recordException(e);', 'e.printStackTrace();\n            com.doubleangels.redact.sentry.SentryManager.recordException(e);')

with open('app/src/main/java/com/doubleangels/redact/media/FormatConverter.java', 'w') as f:
    f.write(content)
