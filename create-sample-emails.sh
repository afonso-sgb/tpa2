#!/bin/bash

# create-sample-emails.sh
# Creates sample email files for testing

OUTPUT_DIR=${1:-./EmailFiles}

mkdir -p "$OUTPUT_DIR"

echo "Creating sample email files in $OUTPUT_DIR..."

# Email 1
cat > "$OUTPUT_DIR/email001.txt" << 'EOF'
From: alice@example.com
To: team@example.com
Subject: Team Meeting Tomorrow
Date: 2025-12-05

Hi everyone,

This is a reminder about our team meeting scheduled for tomorrow at 10:00 AM.
Please review the project status before the meeting.

Best regards,
Alice
EOF

# Email 2
cat > "$OUTPUT_DIR/email002.txt" << 'EOF'
From: bob@example.com
To: alice@example.com
Subject: Project Update
Date: 2025-12-05

Hello Alice,

The project milestone has been completed successfully.
The deadline for the next phase is approaching next week.

Thanks,
Bob
EOF

# Email 3
cat > "$OUTPUT_DIR/email003.txt" << 'EOF'
From: carol@example.com
To: team@example.com
Subject: Meeting Rescheduled
Date: 2025-12-06

Dear team,

The meeting scheduled for tomorrow has been rescheduled to the afternoon at 2:00 PM.

Regards,
Carol
EOF

# Email 4
cat > "$OUTPUT_DIR/email004.txt" << 'EOF'
From: david@example.com
To: all@example.com
Subject: Server Maintenance Notice
Date: 2025-12-04

Important Notice:

Scheduled server maintenance will occur this weekend.
All services will be temporarily unavailable during this time.

IT Department
EOF

# Email 5
cat > "$OUTPUT_DIR/email005.txt" << 'EOF'
From: eve@example.com
To: bob@example.com
Subject: Re: Project Update
Date: 2025-12-05

Hi Bob,

Great work on completing the milestone!
I'll schedule a review meeting for next week.

Cheers,
Eve
EOF

echo "Created $(ls -1 $OUTPUT_DIR/*.txt | wc -l) sample email files"
