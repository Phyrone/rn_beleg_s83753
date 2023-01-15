#!/bin/bash

#
# @file     test_/beleg_lokal.sh
# @brief    Verifiziert ein gegebener RN Beleg und fuehrt mit diesem
#           diverse Tests durch. Die Tests lehnen sich an dem
#           Dokument bzgl. des Abgabeformats an.
# @author   Raphael Pour <s74020@informatik.htw-dresden.de>
# @help     https://bitbucket.org/evilc00kie/rn_beleg
# @date     08-2017
#

#
# CONFIGURATION
#

# Define mandatory files/directories which existens should be tested
mandatory_files=( "filetransfer" "README.md" "make.sh" )
mandatory_directories=( "doc" "bin" "src")
ignored_extensions=( "jpg" "pdf" "png" "gif" "jpeg" "docx" "tex" "tikz" "doc" "class")

# Define scripts where the x right should be set via chmod
# This set should be a subset of the set $mandatory_files
accessable_scripts=( "filetransfer" "make.sh" )

# Name of the Module
#modul="Rechnernetze/Kommunikationssysteme"
#module=""

# Banner
hint="Skript um einen Beleg auf häufig auftretende Fehler zu testen.
Inhaltliche Fehler im Java-Quellcode oder in den Shell-Skripten werden
nicht berücksichtigt. D.h. erfolgreiches Compilieren heißt nicht, dass der
Beleg semantisch funktionsfähig ist."

# Short description how to use this script
usage="Syntax: $0 <Archiv>\nArchiv\tDateiname des Belegs welcher als tar oder tar.gz vorliegt."

# Testing the server/client
timeout=600
port=3333
stats=stats.txt
testfile=random.txt
testfilesize=3000 # twice as big as the max. allowed MTU by every ethernet
debug=0

#
# CHECK SCRIPT
#

# == Checklist ==
# Archivename
# Mandatory Files
# Mandatory Directories
# Charset
# Lineending
# Access rights
# Makefile


# Check arguments
if [[ $# -ne 1 ]]
then
    echo "Ungueltige Argumentenangabe"
    echo -e $usage
    exit 1
fi

echo -e "==$modul Beleg Testskript==\n"
echo -e "$hint\n"

archive="$1"

#IF ARCHIVE EXTENSION CAN ALSO BE ONLY TAR
# Check syntax of the archive name
#if [[ $archive =~ s[0-9]{5}\.tar$  ]]
#then
#    without_extension=${archive%.*}
#elif [[ $archive =~ s[0-9]{5}\.tar\.gz$  ]]
#IF ARCHIVE EXTENSION CAN ALSO BE ONLY TAR

if [[ $archive =~ s[0-9]{5}\.tar\.gz$  ]]
then
    without_extension=${archive%.*.*}
else
    echo -e "Ungueltiger Archivname!\nArchivname: <s-nummer>.tar.gz"
    exit 1
fi

beleg_directory=$(basename $without_extension)

# Check if archive exists
if [[ ! -f $archive ]]
then
    echo "Archiv '$archive' konnte nicht gefunden werden"
    exit 1
fi

# Create new directory where the archive should be extracted to
# Check if the directory is already existing and delete it
if [[ -d "$beleg_directory" ]]
then
    $(rm -rf $beleg_directory)
fi

# Extract archive
tar -xf $archive

if [[ $? -ne 0 ]]
then
    echo "Fehler beim Entpacken des Archivs."
    exit 1
fi

# Check if mandatory files are existing
for file in ${mandatory_files[@]}
do
    if [[ $debug -ne 0 ]]
    then
        echo $beleg_directory/$file
    fi

    if [[ ! -f "$beleg_directory/$file"  ]]
    then
        echo "Datei $file fehlt"
        exit 1
    fi
done

# Check if mandatory directories are existing
for directory in ${mandatory_directories[@]}
do
    if [[ ! -d "$beleg_directory/$directory"  ]]
    then
        echo "Verzeichnis $directory fehlt"
        exit 1
    fi
done


# Check Charset is set to utf8 and lineending to LF Linux (LF) in each file
for file in $(find "$beleg_directory" ! -path . -type f)
do
    fileLower=$(echo "$file"| tr '[:upper:]' '[:lower:]')

    # Overstep all files having extensions from the
    # ignored_extensions list
    # This ensures that we don't check the charset of
    # those files being for example in binary format
    for extension in ${ignored_extensions[@]}
    do
        if [[ $fileLower == *"$extension"* ]]
        then
            continue 2
        fi
    done

    # Overstep the archive itself
    if [[ $fileLower == *"$archive"* ]]
    then
        continue
    fi

    # Check if file is empty
    if [[ ! -s $file ]]
    then
        echo "$file ist leer oder existiert nicht."
        exit 1
    fi

    # Get charset of the current file
    charset=$(file -i "$file")

    if [[ $debug -ne 0 ]]
    then
        echo "file -i $file: $charset"
    fi
    # Since us-ascii is 7-bit ASCII which is identical to utf-8
    # on the first 128 chars (and us-ascii hasn't more)
    # we also have to check for us-ascii.
    # file -I returns us-ascii if a utf-8 file only contains
    # chars from the first 128 chars of the charset

    if [[ $charset == *"binary"* ]]
    then
        echo "Die Datei $file scheint eine Testdatei zu sein (da Binärformat vorliegt). Bitte diese Datei entfernen. Testdateien machen den Beleg nur unnötig groß."
        exit 1
    elif [[ ! $charset == *"utf-8"* && ! $charset == *"us-ascii" ]]
    then
        echo "$file ist nicht UTF-8 kodiert."
        exit 1
    fi

    # Check line ending
    lineending_result=$(file "$file")
    if [[ $lineending_result == *"with CRLF"* ]]
    then
        echo "Datei $file hat keine Unix-Zeilenenden. Es wurden Windows-Zeilenenden erkannt. Bitte manuell oder mit dos2unix ändern"
        exit 1
    fi
done

# Check access rights of all scripts
for file in ${accessable_scripts[@]}
do

    full_file_path="$beleg_directory/$file"
    if [[ ! -x $full_file_path ]]
    then
        echo "$file ist nicht ausführbar. Bitte mit 'chmod +x <file>' die Skripte ausfuehrbar machen"
        exit 1
    fi
done

# Check if files can be compiled
cd $beleg_directory

# Remove all precompiled files in order to test if the
# make file generates something
rm -rf ./bin/* 2> /dev/null

echo "Ausgabe Makefile ==========="
./make.sh
make_error_code=$?
echo "Ende Ausgabe Makefile ======"

if [[ $make_error_code -ne 0 ]]
then
    echo "Beleg konnte nicht kompiliert werden. Siehe Fehlerausgabe des Makefiles"
    exit 1
fi

# Check if make file adds files to the bin directory.
# Javac should move the compiled class files into
# this directory
if [[ ! "$(ls -A ./bin/)"  ]]
then
    echo "Compilierte Dateien fehlen oder sind im falschen Verzeichnis"
    exit 1
fi

cd ..

#
# Execute Client and Server and check if a transmission is successful
#

# Create random file
cat /dev/urandom | tr -cd 'a-f0-9' | head -c $testfilesize >> $testfile
crc_a=$(crc32 $testfile)

# Execute server and return
cd $beleg_directory
./filetransfer server $port 0.1 150&
cd ..

# Save PID in order to kill it later
server_pid=$!

# Execute client and trigger upload to server. This returns after the client finishes
#./$beleg_directory/client-udp localhost 3333 random.txt

echo "Ausgabe Client ==========="
command time -f "%e" -a -o $stats   timeout $timeout   ./$beleg_directory/filetransfer client localhost $port $testfile
echo "Ende Ausgabe Makefile ==========="

echo -n "Time needed:"
cat $stats

if [[ ! -f ./$beleg_directory/$testfile ]]
then
    echo "Fehler bei Testübertragung: Datei wurde nicht übertragen."
    rm $testfile
    pkill -P $server_pid
    exit -1
fi

crc_b=$(crc32 ./$beleg_directory/$testfile)

if [[ "$crc_a" -ne "$crc_b"  ]]
then
    echo "Fehler bei Testübertragung: CRC der Zieldatei stimmt nicht mit der Quelldatei überein"
    rm $tesfile
    pkill -P $server_pid
    exit 1
fi

rm $testfile
pkill -P $server_pid

echo "$archive OK"

exit 1